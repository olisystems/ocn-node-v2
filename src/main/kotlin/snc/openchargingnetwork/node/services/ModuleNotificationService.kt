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
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.generateUUIDv4Token

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
    private val ocnRulesService: OcnRulesService
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
        var changedPlatformId = changedPlatform?.id;

        if(changedPlatform == null) {
            val roles = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyId);
            if(roles.count() > 0) {
                changedPlatformId = roles.first().platformID;
            }
        }

        for (platform in platformRepo.findAll()) {
            // Only push the update if the platform is connected and it isn't the platform that triggered
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
                        // Only push the update if the role has whitelisted the module owner
                        val counterParty = BasicRole(id = partyId, country = countryCode)
                        if (ocnRulesService.isWhitelisted(platform, counterParty)) {
                            clientsToNotify.add(clientRole)
                        }
                    }
                }
            }
        }

        return clientsToNotify
    }

    /** Send a notification of a module change to a list of parties */
    fun notifyPartiesOfModuleChange(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any,
        urlPath: String
    ) {
        for (party in parties) {
            val tokenB = platformRepo.findById(party.platformID).get().auth.tokenB
            if (tokenB != null) {
                notifyPartyOfModuleChange(
                    moduleId,
                    party.partyID,
                    party.countryCode,
                    tokenB,
                    changedData,
                    urlPath
                )
            }
        }
    }

    /** Send a notification of a module change to a list of parties asynchronously */
    @Async
    fun notifyPartiesOfModuleChangeAsync(
        moduleId: ModuleID,
        parties: Iterable<RoleEntity>,
        changedData: Any,
        urlPath: String
    ) {
        logger.info(
            "Starting async notification of ${moduleId.id} change to ${parties.count()} parties"
        )
        notifyPartiesOfModuleChange(moduleId, parties, changedData, urlPath)
        logger.info("Completed async notification of ${moduleId.id} change")
    }

    fun notifyPartyOfModuleChange(
        moduleId: ModuleID,
        partyId: String,
        countryCode: String,
        tokenB: String,
        changedData: Any,
        urlPath: String
    ) {
        val sender =
            BasicRole(
                id = "OCN",
                country = "CH"
            ) // TODO: put node platformID and countryCode in a shared, configurable location

        val receiver = BasicRole(partyId, countryCode)
        val requestVariables =
            OcpiRequestVariables(
                module = moduleId,
                interfaceRole = InterfaceRole.RECEIVER,
                method = HttpMethod.PUT,
                headers =
                    OcnHeaders(
                        authorization = "Token ${tokenB}",
                        requestID = generateUUIDv4Token(),
                        correlationID = generateUUIDv4Token(),
                        sender = sender,
                        receiver = receiver
                    ),
                body = changedData,
                urlPath = urlPath
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
