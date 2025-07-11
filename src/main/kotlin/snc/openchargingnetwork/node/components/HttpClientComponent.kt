package snc.openchargingnetwork.node.components

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.toMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.RequestPredicates
import snc.openchargingnetwork.node.models.ControllerResponse
import snc.openchargingnetwork.node.models.GqlCertificateData
import snc.openchargingnetwork.node.models.GqlPartiesAndOpsData
import snc.openchargingnetwork.node.models.GqlQuery
import snc.openchargingnetwork.node.models.GqlResponse
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.OcnMessageHeaders
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.SyncedHttpResponse
import snc.openchargingnetwork.node.models.exceptions.OcpiServerGenericException
import snc.openchargingnetwork.node.models.exceptions.OcpiServerUnusableApiException
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.models.ocpi.VersionDetail
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
import snc.openchargingnetwork.node.tools.urlJoin

@Component
class HttpClientComponent {

    val mapper = jacksonObjectMapper()

    val configurationModules: List<ModuleID> = listOf(ModuleID.CREDENTIALS)

    fun convertToRequestVariables(stringBody: String): OcpiRequestVariables = mapper.readValue(stringBody)

    val client = HttpClient(CIO)

    private companion object {
        const val OCN_MESSAGE_ENDPOINT = "/ocn/message"
    }

    /**
     * General purpose Http Request wrapper around async call from Ktor Client
     */
    fun sendHttpRequest(
        endpoint: String,
        method: HttpMethod,
        body: Any? = null,
        headers: Map<String, String> = mapOf(),
        queryParams: Map<String, String> = mapOf()
    ): SyncedHttpResponse {
        return runBlocking {
            val response = client.request(endpoint) {
                this.method = when (method) {
                    HttpMethod.GET -> io.ktor.http.HttpMethod.Get
                    HttpMethod.POST -> io.ktor.http.HttpMethod.Post
                    HttpMethod.PUT -> io.ktor.http.HttpMethod.Put
                    HttpMethod.DELETE -> io.ktor.http.HttpMethod.Delete
                    HttpMethod.PATCH -> io.ktor.http.HttpMethod.Patch
                    HttpMethod.HEAD -> io.ktor.http.HttpMethod.Head
                    HttpMethod.OPTIONS -> io.ktor.http.HttpMethod.Options
                    else -> {
                        io.ktor.http.HttpMethod.Get
                    }
                }
                headers.forEach { (key, value) ->
                    header(key, value)
                }
                url {
                    queryParams.forEach { (key, value) ->
                        parameters.append(key, value)
                    }
                }
                queryParams.forEach { (key, value) ->
                    RequestPredicates.param(key, value)
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

            val syncedSyncedHttpResponse = SyncedHttpResponse(
                response.status,
                response.headers,
                response.contentType(),
                response.contentLength(),
                response.bodyAsText()
            )
            return@runBlocking syncedSyncedHttpResponse
        }
    }

    /**
     * Makes an OCPI HTTP request to the given URL with the specified method, headers, query parameters, and body.
     *
     * @param method The HTTP method to be used for the request (e.g., GET, POST).
     * @param url The target URL for the OCPI request.
     * @param headers The HTTP headers to include in the request, with each key being the header name and the value being the header value.
     * @param queryParams The query parameters to append to the request URL as key-value pairs. Defaults to null if no query parameters are provided.
     * @param body The request body as a String, if applicable. Defaults to null if no body is needed.
     * @return An OcpiHttpResponse object containing the response status code, headers, and parsed body of type T.
     * @throws snc.openchargingnetwork.node.models.exceptions.OcpiServerGenericException if the JSON response cannot be parsed or a generic server error occurs.
     */
    fun <T : Any> makeOcpiRequest(
        method: HttpMethod,
        url: String,
        headers: Map<String, String?>,
        queryParams: Map<String, Any?>? = null,
        body: String? = null
    ): OcpiHttpResponse<T> {
        val stringHeaders = headers.mapValues { (_, value) -> value.toString() }
        val stringQueryParams = queryParams?.mapValues { (_, value) -> value.toString() } ?: mapOf()
        val response = sendHttpRequest(url, method, body, stringHeaders, stringQueryParams)
        try {
            return OcpiHttpResponse(
                statusCode = response.statusCode.value,
                headers = response.headers.toMap().mapValues { (_, value) -> value.toString() },
                body = mapper.readValue(response.body),
            )
        } catch (e: JsonParseException) {
            throw OcpiServerGenericException("Could not parse JSON response of forwarded OCPI request: ${e.message}")
        }
    }


    /**
     * Makes an OCPI request to the specified URL using the provided headers and request variables.
     *
     * @param url The endpoint URL to which the OCPI request will be made.
     * @param ocnHeaders The headers to be included in the request, including routing and other specific headers.
     * @param requestVariables The parameters and body for the request, encapsulated in an `OcpiRequestVariables` object.
     * @return An `OcpiHttpResponse` object containing the response details, including HTTP status, headers, and parsed body.
     */
    final fun <T : Any> makeOcpiRequest(
        url: String,
        ocnHeaders: OcnHeaders,
        requestVariables: OcpiRequestVariables
    ): OcpiHttpResponse<T> {

        // includes or excludes routing headers based on module type (functional or configuration)
        // TODO: credentials and versions must also include X-Request-ID/X-Correlation-ID
        val headersMap = ocnHeaders.toMap(routingHeaders = !configurationModules.contains(requestVariables.module))

        var jsonBody: String? = null
        if (requestVariables.body != null) {
            // Setting content-type to json as this is the expected format for standard and custom OCPI modules
            headersMap["content-type"] = "application/json"
            // If the request body is a String, we assume that it is already JSON
            jsonBody = requestVariables.body as? String ?: mapper.writeValueAsString(
                requestVariables.body
            )
        }

        return makeOcpiRequest(
            method = requestVariables.method,
            url = url,
            headers = headersMap,
            queryParams = requestVariables.queryParams,
            body = jsonBody
        )
    }


    /**
     * Retrieves available versions from the specified URL using the provided authorization token.
     *
     * @param url The endpoint URL from which to request the version information.
     * @param authorization The authorization token to be used for the request.
     * @return A list of `Version` objects representing the available OCPI versions.
     * @throws snc.openchargingnetwork.node.models.exceptions.OcpiServerUnusableApiException If the response contains an unexpected HTTP status code,
     * an unexpected OCPI status code, missing or invalid version data, or if there is an error
     * during request execution or response parsing.
     */
    fun getVersions(url: String, authorization: String): List<Version> {
        try {
            val response = sendHttpRequest(
                endpoint = url,
                method = HttpMethod.GET,
                headers = mapOf(
                    "Authorization" to "Token $authorization",
                    "X-Correlation-ID" to generateUUIDv4Token(),
                    "X-Request-ID" to generateUUIDv4Token()
                )
            )

            val body: OcpiResponse<List<Version>> = mapper.readValue(response.body)

            when {
                !response.statusCode.toString().startsWith("2") ->
                    throw OcpiServerUnusableApiException("Unexpected HTTP status code: ${response.statusCode}")

                body.statusCode != 1000 ->
                    throw OcpiServerUnusableApiException("Unexpected OCPI status code: ${body.statusCode} - ${body.statusMessage}")

                body.data == null ->
                    throw OcpiServerUnusableApiException("No version data received")

                else -> return body.data
            }
        } catch (e: JsonProcessingException) {
            throw OcpiServerUnusableApiException("Failed to parse response: ${e.message}")
        } catch (e: Exception) {
            throw OcpiServerUnusableApiException("Failed to request from $url: ${e.message}")
        }
    }


    /**
     * Retrieves the version details from the specified URL using the provided authorization token.
     *
     * @param url The endpoint URL to request version details from.
     * @param authorization The authorization token required to authenticate the request.
     * @return The version details encapsulated in a `VersionDetail` object.
     * @throws OcpiServerUnusableApiException If the response contains an unexpected HTTP status, unexpected OCPI status code,
     *        missing or invalid version detail data, or if there is an error during response parsing or request execution.
     */
    fun getVersionDetail(url: String, authorization: String): VersionDetail {
        try {
            val response = sendHttpRequest(
                endpoint = url,
                method = HttpMethod.GET,
                headers = mapOf(
                    "Authorization" to "Token $authorization",
                    "X-Correlation-ID" to generateUUIDv4Token(),
                    "X-Request-ID" to generateUUIDv4Token()
                )
            )

            val body: OcpiResponse<VersionDetail> = mapper.readValue(response.body)

            when {
                !response.statusCode.toString().startsWith("2") ->
                    throw OcpiServerUnusableApiException("Unexpected HTTP status code: ${response.statusCode}")

                body.statusCode != 1000 ->
                    throw OcpiServerUnusableApiException("Unexpected OCPI status code: ${body.statusCode} - ${body.statusMessage}")

                body.data == null ->
                    throw OcpiServerUnusableApiException("No version detail data received")

                else -> return body.data
            }
        } catch (e: JsonProcessingException) {
            throw OcpiServerUnusableApiException("Failed to parse version details response: ${e.message}")
        } catch (e: Exception) {
            throw OcpiServerUnusableApiException("Failed to request version details from $url: ${e.message}")
        }
    }

    /**
     * Makes a POST request to an OCN Node's message endpoint.
     * Used to forward requests to OCPI platforms without a direct local connection.
     *
     * @param url The base URL of the OCN node
     * @param headers The OCN-specific message headers
     * @param body The request body as a string
     * @return OcpiHttpResponse containing the parsed response of type T
     * @throws OcpiServerGenericException if the request fails or response cannot be parsed
     */
    fun <T : Any> postOcnMessage(
        url: String,
        headers: OcnMessageHeaders,
        body: String
    ): OcpiHttpResponse<T> {
        val fullUrl = urlJoin(url, OCN_MESSAGE_ENDPOINT)

        try {
            val response = sendHttpRequest(
                endpoint = fullUrl,
                method = HttpMethod.POST,
                body = body,
                headers = headers.toMap()
            )

            return OcpiHttpResponse(
                statusCode = response.statusCode.value,
                headers = response.headers.toMap().mapValues { it.value.toString() },
                body = mapper.readValue(response.body)
            )
        } catch (e: JsonParseException) {
            throw OcpiServerGenericException("Failed to parse OCN message response: ${e.message}")
        } catch (e: Exception) {
            throw OcpiServerGenericException("Failed to post OCN message: ${e.message}")
        }
    }

    /**
     * Updates the OCN client information by sending a PUT request to the specified URL.
     *
     * @param url The base URL of the OCN node to which the client information is being updated.
     * @param signature The OCN-specific signature used to authorize the request.
     * @param body The details of the client being updated, encapsulated in a `ClientInfo` object.
     */
    fun putOcnClientInfo(url: String, signature: String, body: ClientInfo) {
        val headers = mapOf("OCN-Signature" to signature)
        val endpoint = urlJoin(url, "/ocn/client-info")
        val bodyString = mapper.writeValueAsString(body)
        sendHttpRequest(endpoint, HttpMethod.PUT, bodyString, headers)
    }

    /**
     * Sends a GraphQL query to the specified URL to fetch a list of parties from the indexed OCN registry.
     *
     * @param url The endpoint URL to send the GraphQL request.
     * @param authorization The bearer token used for authorization with the specified URL.
     * @param query The GraphQL query string to execute.
     * @return A ControllerResponse containing a list of Party objects if the operation is successful,
     *         or an error message in case of failure.
     */
    fun getIndexedOcnRegistry(url: String, authorization: String, query: String):
            ControllerResponse<GqlPartiesAndOpsData> = runBlocking {
        try {
            val query = GqlQuery(
                query = query.trimIndent(),
                operationName = "Subgraphs",
                variables = emptyMap()
            )

            val response = sendHttpRequest(
                endpoint = url,
                method = HttpMethod.POST,
                body = Json.Default.encodeToString(query),
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer $authorization",
                    HttpHeaders.ContentType to ContentType.Application.Json.toString()
                )
            )

            if (!response.statusCode.isSuccess()) {
                return@runBlocking ControllerResponse(
                    false, null,
                    "getIndexedOcnRegistry returned HTTP ${response.statusCode}; Error: ${response.body}"
                )
            }

            val queryResult: GqlResponse<GqlPartiesAndOpsData> = Json.Default.decodeFromString(response.body)

            return@runBlocking when {
                // Error
                queryResult.errors != null -> ControllerResponse(
                    false, null,
                    "getIndexedOcnRegistry query error: ${queryResult.errors}"
                )
                // Success
                queryResult.data != null -> ControllerResponse(true, queryResult.data)
                // Undefined behaviour
                else -> ControllerResponse(
                    false, null,
                    "No data received from the GraphQL query"
                )
            }

        } catch (e: Exception) {
            ControllerResponse(false, null, "Unexpected error: ${e.message}")
        }
    }

    /**
     *
     */
    fun getIndexedOcnRegistryCertificates(url: String, authorization: String, query: String):
            ControllerResponse<GqlCertificateData> = runBlocking {
        try {
            val query = GqlQuery(
                query = query.trimIndent(),
                operationName = "Subgraphs",
                variables = emptyMap()
            )

            val response = sendHttpRequest(
                endpoint = url,
                method = HttpMethod.POST,
                body = Json.Default.encodeToString(query),
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer $authorization",
                    HttpHeaders.ContentType to ContentType.Application.Json.toString()
                )
            )

            if (!response.statusCode.isSuccess()) {
                return@runBlocking ControllerResponse(
                    false, null,
                    "getIndexedOcnRegistryCertificates returned HTTP ${response.statusCode}; Error: ${response.body}"
                )
            }

            val queryResult: GqlResponse<GqlCertificateData> = Json.Default.decodeFromString(response.body)

            return@runBlocking when {
                // Error
                queryResult.errors != null -> ControllerResponse(
                    false, null,
                    "getIndexedOcnRegistryCertificates query error: ${queryResult.errors}"
                )
                // Success
                queryResult.data != null -> ControllerResponse(true, queryResult.data)
                // Undefined behaviour
                else -> ControllerResponse(
                    false, null,
                    "No data received from the GraphQL query"
                )
            }

        } catch (e: Exception) {
            ControllerResponse(false, null, "Unexpected error: ${e.message}")
        }
    }

    /**
     * Sends a GraphQL query to the specified URL to fetch a list of operators from the indexed OCN registry.
     *
     * @param url The endpoint URL to send the GraphQL request.
     * @param authorization The bearer token used for authorization with the specified URL.
     * @param query The GraphQL query string to execute.
     * @return A ControllerResponse containing a list of Operator objects if the operation is successful,
     *         or an error message in case of failure.
     */
    fun getIndexedOcnRegistryOperators(url: String, authorization: String, query: String):
            ControllerResponse<GqlData> = runBlocking {
        try {
            val gqlQuery = GqlQuery(
                query = query.trimIndent(),
                operationName = "Subgraphs",
                variables = emptyMap()
            )

            val response = sendHttpRequest(
                endpoint = url,
                method = HttpMethod.POST,
                body = Json.Default.encodeToString(gqlQuery),
                headers = mapOf(
                    HttpHeaders.Authorization to "Bearer $authorization",
                    HttpHeaders.ContentType to ContentType.Application.Json.toString()
                )
            )

            if (!response.statusCode.isSuccess()) {
                return@runBlocking ControllerResponse(
                    false, null,
                    "getIndexedOcnRegistryOperators returned HTTP ${response.statusCode}; Error: ${response.body}"
                )
            }

            val queryResult: GqlResponse<GqlData> = Json.Default.decodeFromString(response.body)

            return@runBlocking when {
                // Error
                queryResult.errors != null -> ControllerResponse(false, null,
                    "getIndexedOcnRegistryOperators query error: ${queryResult.errors}")
                // Success
                queryResult.data != null -> ControllerResponse(true, queryResult.data)
                // Undefined behaviour
                else -> ControllerResponse(false, null,
                    "No data received from the GraphQL query")
            }

        } catch (e: Exception) {
            ControllerResponse(false, null,"Unexpected error: ${e.message}")
        }
    }

    /**
     * Fetches both operators and parties from the indexed OCN registry in parallel.
     *
     * @param url The endpoint URL to send the GraphQL requests.
     * @param authorization The bearer token used for authorization with the specified URL.
     * @param operatorsQuery The GraphQL query string for fetching operators.
     * @param partiesQuery The GraphQL query string for fetching parties.
     * @return A pair containing ControllerResponse for operators and parties respectively.
     */
    fun getIndexedOcnRegistryOperatorsAndParties(
        url: String, 
        authorization: String, 
        operatorsQuery: String,
        partiesQuery: String
    ): Pair<ControllerResponse<GqlData>, ControllerResponse<GqlData>> = runBlocking {
        // Execute both queries in parallel
        val operatorsDeferred = async { getIndexedOcnRegistryOperators(url, authorization, operatorsQuery) }
        val partiesDeferred = async { getIndexedOcnRegistryParties(url, authorization, partiesQuery) }
        
        val operatorsResponse = operatorsDeferred.await()
        val partiesResponse = partiesDeferred.await()
        
        return@runBlocking Pair(operatorsResponse, partiesResponse)
    }
}