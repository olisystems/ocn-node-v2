package snc.openchargingnetwork.node.controllers.ocpi.v2_2

import org.springframework.http.HttpMethod
import org.springframework.http.HttpRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.plugins.core.CustomModule
import snc.openchargingnetwork.node.plugins.core.CustomModuleRequest

@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}/ocpi/custom/")
@RestController
class CustomModulesController(
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    customModules: List<CustomModule>
) {

    private val modulesById =
            buildMap {
                customModules.forEach { module -> putIfAbsent(module.moduleId().lowercase(), module) }
            }

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

        val sender = BasicRole(fromPartyID, fromCountryCode)
        val receiver = BasicRole(toPartyID, toCountryCode)
        val urlPath = extractCustomModuleUrlPath(request, interfaceRole, module)
        val method = HttpMethod.valueOf(request.method.toString())
        val headers = OcnHeaders(authorization, signature, requestID, correlationID, sender, receiver)

        val requestVariables = OcpiRequestVariables(
            module = ModuleID.CUSTOM,
            customModuleId = module,
            interfaceRole = InterfaceRole.resolve(interfaceRole),
            method = method,
            headers = headers,
            urlPath = urlPath,
            queryParams = queryParams,
            body = body
        )

        val pluginHandler = modulesById[module.lowercase()]
        if (pluginHandler != null) {
            val validated =
                    requestHandlerBuilder.build<Any>(requestVariables).validateIncoming()
            val pluginRequest = CustomModuleRequest(
                interfaceRole = interfaceRole,
                customModuleId = module,
                urlPath = urlPath,
                method = method,
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
                data = response.data,
                verificationStatus = validated.verificationStatus?.name
            )
            // OCPI status lives in the body; HTTP status is always OK for handled custom modules.
            return ResponseEntity.status(HttpStatus.OK).body(ocpiResponse)
        }

        return requestHandlerBuilder
            .build<Any>(requestVariables)
            .forwardDefault()
            .getResponseWithAllHeaders()
    }

}

/**
 * Returns the path segment after `/ocpi/custom/{interfaceRole}/{module}`, or null when absent.
 * Uses [java.net.URI.getPath] so scheme/host and query are never included.
 */
internal fun extractCustomModuleUrlPath(
        request: HttpRequest,
        interfaceRole: String,
        module: String
): String? {
    return try {
        extractCustomModuleUrlPath(request.uri.path, interfaceRole, module)
    } catch (_: IllegalStateException) {
        null
    }
}

internal fun extractCustomModuleUrlPath(
        path: String,
        interfaceRole: String,
        module: String
): String? {
    val marker = "/ocpi/custom/$interfaceRole/$module"
    val idx = path.indexOf(marker)
    if (idx < 0) return null
    return path.substring(idx + marker.length).takeIf { it.isNotEmpty() }
}
