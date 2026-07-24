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

package snc.openchargingnetwork.node.services

import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
import snc.openchargingnetwork.node.tools.toBs64String

/**
 * Generic service for handling module notifications to connected parties This service manages OCPI
 * module notifications with comprehensive functionality Uses event-driven architecture for
 * broadcasting changes across different modules
 */
@Service
class ModuleNotificationService(
    private val platformRepo: PlatformRepository,
    private val roleRepo: RoleRepository,
    private val endpointRepo: EndpointRepository,
    private val httpClientComponent: HttpClientComponent,
    private val routingService: RoutingService,
    private val ocnRulesService: OcnRulesService,
    private val nodeProperties: NodeProperties
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ModuleNotificationService::class.java)
    }

    /**
     * Get parties who should be sent a module Push notification (sans the changedPlatform if
     * provided)
     */
    fun getPartiesToNotifyOfModuleChange(
        moduleId: ModuleID,
        changedPlatform: PlatformEntity? = null,
        partyId: String,
        countryCode: String
    ): List<RoleEntity> {
        val clientsToNotify = mutableListOf<RoleEntity>()
        var changedPlatformId = changedPlatform?.id

        if (changedPlatform == null) {
            val roles = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyId)
            if (roles.count() > 0) {
                changedPlatformId = roles.first().platformID
            }
        }

        for (platform in platformRepo.findAll()) {
            // Only push the update if the platform is connected and it isn't the platform that
            // triggered
            // the event
            if (platform.status == ConnectionStatus.CONNECTED && platform.id != changedPlatformId) {
                // Only push the update if the platform has implemented the module Receiver endpoint
                val modulePutEndpoint =
                    endpointRepo.findFirstByPlatformIDAndIdentifierAndRoleOrderByIdAsc(
                        platformID = platform.id,
                        identifier = moduleId.id,
                        Role = InterfaceRole.RECEIVER
                    )

                if (modulePutEndpoint != null) {
                    for (clientRole in roleRepo.findAllByPlatformID(platform.id)) {
                        // Never notify the object/module owner back to itself
                        if (isSameParty(clientRole.countryCode, clientRole.partyID, countryCode, partyId)) {
                            continue
                        }
                        // Only push the update if the role has whitelisted the module owner
                        // and is an applicable counterpart for this module (not every role).
                        val counterParty = BasicRole(id = partyId, country = countryCode)
                        if (
                            ocnRulesService.isWhitelisted(platform, counterParty) &&
                                isCounterpartRole(moduleId, clientRole.role)
                        ) {
                            clientsToNotify.add(clientRole)
                        }
                    }
                }
            }
        }

        return clientsToNotify
    }

    /**
     * OCPI Broadcast Push targets applicable opposite roles, not every role on a platform.
     * Locations/tariffs originate from CPOs; tokens from eMSPs.
     */
    private fun isCounterpartRole(moduleId: ModuleID, recipientRole: Role): Boolean {
        return when (moduleId) {
            ModuleID.LOCATIONS,
            ModuleID.TARIFFS ->
                recipientRole in setOf(Role.EMSP, Role.NSP, Role.NAP, Role.HUB, Role.SCSP)
            ModuleID.TOKENS -> recipientRole in setOf(Role.CPO, Role.HUB)
            else -> true
        }
    }

    /** Send a notification of a module change to a list of parties with a custom sender */
    fun notifyPartiesOfModuleChange(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ) {
        for (party in parties) {
            val platform = platformRepo.findById(party.platformID).orElse(null)
            if (platform != null) {
                notifyPartyOfModuleChange(
                    moduleId = moduleId,
                    partyId = party.partyID,
                    countryCode = party.countryCode,
                    authToken = platform.getAuthTokenToIncludeInRequestHeader(),
                    changedData = changedData,
                    urlPath = urlPath,
                    sender = sender,
                    method = method,
                    queryParams = queryParams
                )
            }
        }
    }

    /**
     * Send a notification of a module change to a list of parties asynchronously with a custom
     * sender
     */
    @Async
    fun notifyPartiesOfModuleChangeAsync(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ) {
        logger.info(
            "Starting async notification of ${moduleId.id} change to ${parties.count()} parties (custom sender)"
        )
        notifyPartiesOfModuleChange(
            moduleId,
            parties,
            changedData,
            urlPath,
            sender,
            method,
            queryParams
        )
        logger.info("Completed async notification of ${moduleId.id} change (custom sender)")
    }

    /** Broadcast an object push while preserving its HTTP method, path and query parameters. */
    @Async
    fun broadcastObjectRequestAsync(request: OcpiRequestVariables) {
        val requestSender = request.headers.sender
        val objectOwner = resolveObjectOwner(request)
        // Prefer object owner (path/body country_code + party_id) so a hub re-broadcast
        // does not bounce the object back to the owning CPO/eMSP.
        val ownerCountry = objectOwner?.country ?: requestSender.country
        val ownerPartyId = objectOwner?.id ?: requestSender.id

        val parties =
            getPartiesToNotifyOfModuleChange(
                    moduleId = request.module,
                    partyId = ownerPartyId,
                    countryCode = ownerCountry
                )
                .filterNot { party ->
                    isSameParty(party.countryCode, party.partyID, ownerCountry, ownerPartyId)
                }

        if (objectOwner != null &&
            !isSameParty(objectOwner.country, objectOwner.id, requestSender.country, requestSender.id)
        ) {
            logger.info(
                "Broadcasting {} excluding object owner {}/{} (request sender {}/{})",
                request.module.id,
                objectOwner.country,
                objectOwner.id,
                requestSender.country,
                requestSender.id
            )
        }

        // Hub Broadcast Push must advertise the hub identity in OCPI-from-* headers.
        val hubCountryCode =
            nodeProperties.countryCode
                ?: throw IllegalStateException("ocn.node.countryCode must be configured")
        val hubPartyId =
            nodeProperties.partyId
                ?: throw IllegalStateException("ocn.node.partyId must be configured")
        val hubSender = BasicRole(id = hubPartyId, country = hubCountryCode)

        notifyPartiesOfModuleChange(
            moduleId = request.module,
            parties = parties,
            changedData = request.body,
            urlPath = request.urlPath?.removePrefix("/") ?: "",
            sender = hubSender,
            method = request.method,
            queryParams = request.queryParams
        )
    }

    /**
     * Resolve the OCPI object owner from the request path (`/{country_code}/{party_id}/...`) or
     * body (`country_code` / `party_id`).
     */
    internal fun resolveObjectOwner(request: OcpiRequestVariables): BasicRole? {
        resolveObjectOwnerFromUrlPath(request.urlPath)?.let {
            return it
        }
        return resolveObjectOwnerFromBody(request.body)
    }

    private fun resolveObjectOwnerFromUrlPath(urlPath: String?): BasicRole? {
        if (urlPath.isNullOrBlank()) {
            return null
        }
        val segments = urlPath.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size < 2) {
            return null
        }
        val countryCode = segments[0]
        val partyId = segments[1]
        if (!looksLikeOcpiParty(countryCode, partyId)) {
            return null
        }
        return BasicRole(id = partyId, country = countryCode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveObjectOwnerFromBody(body: Any?): BasicRole? {
        val map =
            when (body) {
                is Map<*, *> -> body as Map<*, *>
                else -> return null
            }
        val countryCode = (map["country_code"] ?: map["countryCode"])?.toString()?.trim()
        val partyId = (map["party_id"] ?: map["partyId"])?.toString()?.trim()
        if (countryCode.isNullOrBlank() || partyId.isNullOrBlank()) {
            return null
        }
        if (!looksLikeOcpiParty(countryCode, partyId)) {
            return null
        }
        return BasicRole(id = partyId, country = countryCode)
    }

    private fun looksLikeOcpiParty(countryCode: String, partyId: String): Boolean {
        return countryCode.length == 2 && partyId.length == 3
    }

    private fun isSameParty(
        countryA: String?,
        partyA: String?,
        countryB: String?,
        partyB: String?
    ): Boolean {
        return !countryA.isNullOrBlank() &&
            !partyA.isNullOrBlank() &&
            countryA.equals(countryB, ignoreCase = true) &&
            partyA.equals(partyB, ignoreCase = true)
    }

    fun notifyPartyOfModuleChange(
        moduleId: ModuleID,
        partyId: String,
        countryCode: String,
        authToken: String,
        changedData: Any?,
        urlPath: String,
        sender: BasicRole,
        method: HttpMethod = HttpMethod.PUT,
        queryParams: Map<String, Any?>? = null
    ) {
        val receiver = BasicRole(partyId, countryCode)
        val requestVariables =
            OcpiRequestVariables(
                module = moduleId,
                interfaceRole = InterfaceRole.RECEIVER,
                method = method,
                headers =
                    OcnHeaders(
                        authorization = "Token ${authToken.toBs64String()}",
                        requestID = generateUUIDv4Token(),
                        correlationID = generateUUIDv4Token(),
                        sender = sender,
                        receiver = receiver
                    ),
                body = changedData,
                urlPath = urlPath,
                queryParams = queryParams
            )

        val (url, headers) =
            routingService.prepareLocalPlatformRequest(requestVariables, proxied = false)

        try {
            httpClientComponent.makeOcpiRequest<Unit>(url, headers, requestVariables)
        } catch (e: Exception) { // fire and forget; catch any error and log
            logger.warn("Error notifying $receiver of ${moduleId.id} change: ${e.message}")
        }
    }
}
