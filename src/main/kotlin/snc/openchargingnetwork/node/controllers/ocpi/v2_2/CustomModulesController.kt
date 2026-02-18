package snc.openchargingnetwork.node.controllers.ocpi.v2_2

import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.plugins.core.CustomModuleRequest
import snc.openchargingnetwork.node.plugins.core.CustomModuleRegistry

@RequestMapping("\${ocn.node.apiPrefix}/ocpi/custom/")
@RestController
class CustomModulesController(
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    private val customModuleRegistry: CustomModuleRegistry
) {

    @RequestMapping("{interfaceRole}/{module}", "/{interfaceRole}/{module}/**")
    fun customModuleMapping(
        @RequestHeader("authorization") authorization: String,
        @RequestHeader("OCN-Signature") signature: String? = null,
        @RequestHeader("X-Request-ID") requestID: String,
        @RequestHeader("X-Correlation-ID") correlationID: String,
        @RequestHeader("OCPI-from-country-code") fromCountryCode: String,
        @RequestHeader("OCPI-from-party-id") fromPartyID: String,
        @RequestHeader("OCPI-to-country-code") toCountryCode: String,
        @RequestHeader("OCPI-to-party-id") toPartyID: String,
        @PathVariable interfaceRole: String,
        @PathVariable module: String,
        @RequestParam queryParams: Map<String, Any>,
        @RequestBody body: String?,
        request: HttpRequest
    ): ResponseEntity<OcpiResponse<Any>> {

        val pluginHandler = customModuleRegistry.getHandler(module)
        if (pluginHandler != null) {
            val urlPath = try {
                request.uri.toString().replace("/ocpi/custom/${interfaceRole}/${module}", "")
                    .takeIf { it.isNotEmpty() }
            } catch (e: IllegalStateException) {
                null
            }
            val pluginRequest = CustomModuleRequest(
                interfaceRole = interfaceRole,
                customModuleId = module,
                urlPath = urlPath,
                method = HttpMethod.valueOf(request.method.toString()),
                queryParams = queryParams,
                body = body,
                fromPartyId = fromPartyID,
                fromCountryCode = fromCountryCode,
                toPartyId = toPartyID,
                toCountryCode = toCountryCode,
                headers = mapOf(
                    "authorization" to authorization,
                    "OCN-Signature" to (signature ?: ""),
                    "X-Request-ID" to requestID,
                    "X-Correlation-ID" to correlationID
                )
            )
            val response = pluginHandler.handle(pluginRequest)
            val ocpiResponse = OcpiResponse<Any>(
                statusCode = response.statusCode,
                statusMessage = response.statusMessage,
                data = response.data
            )
            return ResponseEntity.status(response.statusCode).body(ocpiResponse)
        }

        val sender = BasicRole(fromPartyID, fromCountryCode)
        val receiver = BasicRole(toPartyID, toCountryCode)

        val urlPath = try {
            // TODO: Test this
            request.uri.toString().replace("/ocpi/custom/${interfaceRole}/${module}", "")
        } catch (e: IllegalStateException) { // catch IllegalStateException: request.pathInfo must not be null
            null
        }

        val requestVariables = OcpiRequestVariables(
            module = ModuleID.CUSTOM,
            customModuleId = module,
            interfaceRole = InterfaceRole.resolve(interfaceRole),
            method = HttpMethod.valueOf(request.method.toString()),
            headers = OcnHeaders(authorization, signature, requestID, correlationID, sender, receiver),
            urlPath = urlPath,
            queryParams = queryParams,
            body = body
        )

        return requestHandlerBuilder
            .build<Any>(requestVariables)
            .forwardDefault()
            .getResponseWithAllHeaders()
    }

}