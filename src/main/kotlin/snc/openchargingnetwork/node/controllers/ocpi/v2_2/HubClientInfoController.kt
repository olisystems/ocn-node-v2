/*
    Copyright 2019-2020 eMobility GmbH

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package snc.openchargingnetwork.node.controllers.ocpi.v2_2

import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.HCIProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.ModuleNotificationService
import snc.openchargingnetwork.node.services.RoutingService
import snc.openchargingnetwork.node.services.WalletService
import snc.openchargingnetwork.node.tools.filterNull

@RestController
@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}/ocpi/2.2.1/hubclientinfo")
class HubClientInfoController(
    private val routingService: RoutingService,
    private val hubClientInfoService: HubClientInfoService,
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    private val hciProperties: HCIProperties,
    private val nodeProperties: NodeProperties,
    private val walletService: WalletService,
    private val httpClientComponent: HttpClientComponent,
    private val moduleNotificationService: ModuleNotificationService,
) {

    companion object {
        /** Page size used when a caller does not send a limit. */
        private const val DEFAULT_PAGE_SIZE = 50
    }

    @GetMapping
    fun getHubClientInfo(
        @RequestHeader("authorization") authorization: String,
        @RequestHeader("OCN-Signature", required = false) signature: String? = null,
        @RequestHeader("X-Request-ID", required = false) requestID: String?,
        @RequestHeader("X-Correlation-ID", required = false) correlationID: String?,
        @RequestHeader("OCPI-from-country-code", required = false) fromCountryCode: String?,
        @RequestHeader("OCPI-from-party-id", required = false) fromPartyID: String?,
        @RequestHeader("OCPI-to-country-code", required = false) toCountryCode: String?,
        @RequestHeader("OCPI-to-party-id", required = false) toPartyID: String?,
        @RequestParam("date_from", required = false) dateFrom: String?,
        @RequestParam("date_to", required = false) dateTo: String?,
        @RequestParam("offset", required = false) offset: Int?,
        @RequestParam("limit", required = false) limit: Int?
    ): ResponseEntity<OcpiResponse<Array<ClientInfo>>> {

        // Hub Client Info against this node does not need OCPI routing headers.
        if (isLocalHubReceiver(toCountryCode, toPartyID)) {
            return handleInternalClientInfoRequest(
                fromCountryCode,
                fromPartyID,
                authorization,
                offset,
                limit
            )
        }

        val sender = requireRoutingRole(fromPartyID, fromCountryCode, "OCPI-from")
        val receiver = requireRoutingRole(toPartyID, toCountryCode, "OCPI-to")

        val params =
            mapOf(
                "date_from" to dateFrom,
                "date_to" to dateTo,
                "offset" to offset,
                "limit" to limit
            )
                .filterNull()

        val requestVariables =
            OcpiRequestVariables(
                module = ModuleID.HUB_CLIENT_INFO,
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
                queryParams = params
            )

        return requestHandlerBuilder
            .build<Array<ClientInfo>>(requestVariables)
            .forwardDefault() // retrieves proxied Link response header
            .getResponseWithPaginationHeaders()
    }

    @GetMapping("/{country_code}/{party_id}")
    fun getHubClientInfo(
        @RequestHeader("authorization") authorization: String,
        @RequestHeader("OCN-Signature", required = false) signature: String? = null,
        @RequestHeader("X-Request-ID", required = false) requestID: String?,
        @RequestHeader("X-Correlation-ID", required = false) correlationID: String?,
        @RequestHeader("OCPI-from-country-code", required = false) fromCountryCode: String?,
        @RequestHeader("OCPI-from-party-id", required = false) fromPartyID: String?,
        @RequestHeader("OCPI-to-country-code", required = false) toCountryCode: String?,
        @RequestHeader("OCPI-to-party-id", required = false) toPartyID: String?,
        @PathVariable("country_code") countryCode: String,
        @PathVariable("party_id") partyID: String
    ): ResponseEntity<OcpiResponse<ClientInfo>> {
        if (isLocalHubReceiver(toCountryCode, toPartyID)) {
            if (!fromCountryCode.isNullOrBlank() && !fromPartyID.isNullOrBlank()) {
                routingService.checkSenderKnown(
                    authorization,
                    BasicRole(fromPartyID, fromCountryCode)
                )
            } else {
                routingService.checkSenderKnown(authorization)
            }
            val clientInfo =
                hubClientInfoService.getList(authorization).firstOrNull {
                    it.countryCode.equals(countryCode, ignoreCase = true) &&
                        it.partyID.equals(partyID, ignoreCase = true)
                }
                    ?: throw OcpiClientInvalidParametersException(
                        "Client info not found for $countryCode/$partyID"
                    )
            return ResponseEntity.ok(
                OcpiResponse(statusCode = 1000, data = clientInfo)
            )
        }

        val sender = requireRoutingRole(fromPartyID, fromCountryCode, "OCPI-from")
        val receiver = requireRoutingRole(toPartyID, toCountryCode, "OCPI-to")

        val requestVariables =
            OcpiRequestVariables(
                module = ModuleID.HUB_CLIENT_INFO,
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
                urlPath = countryCode + "/" + partyID
            )

        return requestHandlerBuilder
            .build<ClientInfo>(requestVariables)
            .forwardDefault() // retrieves proxied Link response header
            .getResponseWithPaginationHeaders()
    }

    @PutMapping
    fun updateClientInfo(
        @RequestHeader("OCPI-from-country-code", required = false) fromCountryCode: String?,
        @RequestHeader("OCPI-from-party-id", required = false) fromPartyID: String?,
        @RequestHeader("OCN-Signature", required = false) signature: String?,
        @RequestBody body: String
    ): ResponseEntity<Any> {
        val sender = requireRoutingRole(fromPartyID, fromCountryCode, "OCPI-from")

        if (!nodeProperties.dev && nodeProperties.signatures) {
            if (signature.isNullOrBlank()) {
                throw OcpiClientInvalidParametersException("Missing OCN-Signature header")
            }
            walletService.verify(body, signature, sender)
        }

        val clientInfo: ClientInfo = httpClientComponent.mapper.readValue(body)

        if (!hciProperties.countryCode.equals(sender.country, ignoreCase = true) ||
            !hciProperties.partyId.equals(sender.id, ignoreCase = true)
        ) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body("Invalid Hub Client Info publisher")
        }

        hubClientInfoService.saveClientInfo(clientInfo)

        val parties =
            moduleNotificationService.getPartiesToNotifyOfModuleChange(
                moduleId = ModuleID.HUB_CLIENT_INFO,
                partyId = sender.id,
                countryCode = sender.country
            )

        if (parties.isNotEmpty()) {
            val filteredParties =
                parties.filter {
                    it.countryCode != clientInfo.countryCode && it.partyID != clientInfo.partyID
                }

            val notifySender =
                BasicRole(id = hciProperties.partyId!!, country = hciProperties.countryCode!!)

            moduleNotificationService.notifyPartiesOfModuleChangeAsync(
                moduleId = ModuleID.HUB_CLIENT_INFO,
                parties = filteredParties,
                changedData = clientInfo,
                urlPath = "${clientInfo.countryCode}/${clientInfo.partyID}",
                notifySender
            )
        }

        return ResponseEntity.ok("New client info object stored and broadcasted")
    }

    private fun handleInternalClientInfoRequest(
        fromCountryCode: String?,
        fromPartyID: String?,
        authorization: String,
        offset: Int?,
        limit: Int?
    ): ResponseEntity<OcpiResponse<Array<ClientInfo>>> {
        if (!fromCountryCode.isNullOrBlank() && !fromPartyID.isNullOrBlank()) {
            routingService.checkSenderKnown(authorization, BasicRole(fromPartyID, fromCountryCode))
        } else {
            routingService.checkSenderKnown(authorization)
        }

        val page =
            hubClientInfoService.getPaginatedList(
                fromAuthorization = authorization,
                offset = offset ?: 0,
                limit = limit ?: DEFAULT_PAGE_SIZE
            )

        val headers = HttpHeaders()
        headers["X-Total-Count"] = page.totalCount.toString()
        headers["X-Limit"] = page.limit.toString()

        val nextOffset = page.offset + page.data.size
        if (nextOffset < page.totalCount) {
            val nextUrl =
                "${nodeProperties.url.trimEnd('/')}${nodeProperties.publicPathPrefix()}" +
                    "/ocpi/2.2.1/hubclientinfo?offset=$nextOffset&limit=${page.limit}"
            headers["Link"] = "<$nextUrl>; rel=\"next\""
        }

        return ResponseEntity.ok()
            .headers(headers)
            .body(OcpiResponse(statusCode = 1000, data = page.data.toTypedArray()))
    }

    private fun isLocalHubReceiver(toCountryCode: String?, toPartyID: String?): Boolean {
        if (toCountryCode.isNullOrBlank() || toPartyID.isNullOrBlank()) {
            return true
        }
        return toPartyID.equals(nodeProperties.partyId, ignoreCase = true) &&
            toCountryCode.equals(nodeProperties.countryCode, ignoreCase = true)
    }

    private fun requireRoutingRole(partyId: String?, countryCode: String?, headerPrefix: String): BasicRole {
        if (partyId.isNullOrBlank() || countryCode.isNullOrBlank()) {
            throw OcpiClientInvalidParametersException(
                "$headerPrefix-* headers are required when routing to another party"
            )
        }
        return BasicRole(partyId, countryCode)
    }
}
