package snc.openchargingnetwork.node.controllers.ocpi.v2_2

import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.services.RoutingService
import snc.openchargingnetwork.node.services.VersionsService
import snc.openchargingnetwork.node.tools.urlJoin

@RestController
@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}/ocpi/2.2.1")
class VersionsController(
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    private val versionsService: VersionsService,
    private val routingService: RoutingService,
    private val nodeProperties: NodeProperties
) {

    @GetMapping("/versions")
    fun getVersions(
        @RequestHeader("authorization") authorization: String,
        @RequestHeader("OCN-Signature", required = false) signature: String? = null,
        @RequestHeader("X-Request-ID", required = false) requestID: String?,
        @RequestHeader("X-Correlation-ID", required = false) correlationID: String?,
        @RequestHeader("OCPI-from-country-code", required = false) fromCountryCode: String?,
        @RequestHeader("OCPI-from-party-id", required = false) fromPartyID: String?,
        @RequestHeader("OCPI-to-country-code", required = false) toCountryCode: String?,
        @RequestHeader("OCPI-to-party-id", required = false) toPartyID: String?,
    ): ResponseEntity<OcpiResponse<List<Version>>> {
        // Versions against this node does not need OCPI routing headers.
        if (toCountryCode.isNullOrBlank() || toPartyID.isNullOrBlank() || isNodeIdentity(toCountryCode, toPartyID)) {
            return ResponseEntity.ok(localNodeVersions(authorization))
        }

        val (isLocalParty, versions) = versionsService.getPartyVersions(toCountryCode, toPartyID)

        if (isLocalParty) {
            routingService.checkSenderKnown(authorization)
            return ResponseEntity.ok(
                OcpiResponse(
                    statusCode = OcpiStatus.SUCCESS.code,
                    data = versions
                )
            )
        }

        // Remote party — routing headers are required to forward the request.
        if (fromCountryCode.isNullOrBlank() || fromPartyID.isNullOrBlank()) {
            throw OcpiClientInvalidParametersException(
                "OCPI-from-* headers are required when routing versions to another party"
            )
        }

        val sender = BasicRole(fromPartyID, fromCountryCode)
        val receiver = BasicRole(toPartyID, toCountryCode)

        val requestVariables =
            OcpiRequestVariables(
                module = ModuleID.VERSIONS,
                interfaceRole = InterfaceRole.SENDER,
                method = HttpMethod.GET,
                headers =
                    OcnHeaders(
                        authorization,
                        signature,
                        requestID ?: "",
                        correlationID ?: "",
                        sender,
                        receiver
                    ),
            )

        return requestHandlerBuilder
            .build<List<Version>>(requestVariables)
            .forwardDefault()
            .getResponseWithPaginationHeaders()
    }

    private fun isNodeIdentity(countryCode: String, partyId: String): Boolean {
        return countryCode.equals(nodeProperties.countryCode, ignoreCase = true) &&
            partyId.equals(nodeProperties.partyId, ignoreCase = true)
    }

    private fun localNodeVersions(authorization: String): OcpiResponse<List<Version>> {
        routingService.checkSenderKnown(authorization)
        val endpoint2_2_1 =
            urlJoin(
                nodeProperties.url,
                nodeProperties.apiPrefix,
                nodeProperties.apiPrefixPublic,
                "/ocpi/2.2.1"
            )
        return OcpiResponse(
            OcpiStatus.SUCCESS.code,
            data = listOf(Version("2.2.1", endpoint2_2_1))
        )
    }
}
