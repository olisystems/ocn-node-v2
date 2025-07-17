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

package snc.openchargingnetwork.node.components.listeners

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import snc.openchargingnetwork.node.models.entities.NetworkClientInfoEntity
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.events.*
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.services.HubClientInfoService

@Component
class HubClientInfoListener(
        private val hubClientInfoService: HubClientInfoService,
        private val roleRepo: RoleRepository,
        private val platformRepo: PlatformRepository
) {
    @Async
    @TransactionalEventListener
    fun handlePlatformRegisteredDomainEvent(event: PlatformRegisteredDomainEvent) {
        notifyNetworkOfChanges(event.platform, event.roles)
    }

    @Async
    @TransactionalEventListener
    fun handlePlatformUnregisteredDomainEvent(event: PlatformUnregisteredDomainEvent) {
        notifyNetworkOfChanges(event.platform, event.roles)
    }

    @Async
    @TransactionalEventListener
    fun handlePlatformReconnectedDomainEvent(event: PlatformReconnectedDomainEvent) {
        val roles = roleRepo.findAllByPlatformID(event.platform.id)
        notifyNetworkOfChanges(event.platform, roles)
    }

    @Async
    @TransactionalEventListener
    fun handlePlatformDisconnectedDomainEvent(event: PlatformDisconnectedDomainEvent) {
        val roles = roleRepo.findAllByPlatformID(event.platform.id)
        notifyNetworkOfChanges(event.platform, roles)
    }

    @Async
    @TransactionalEventListener
    fun handlePlannedRoleFoundDomainEvent(event: PlannedRoleFoundDomainEvent) {
        notifyNetworkOfRoleStatusChange(event.role, ConnectionStatus.PLANNED)
    }

    @Async
    @TransactionalEventListener
    fun handleSuspendedRoleFoundDomainEvent(event: SuspendedRoleFoundDomainEvent) {
        notifyNetworkOfRoleStatusChange(event.role, ConnectionStatus.SUSPENDED)
    }

    @Async
    @TransactionalEventListener
    fun handlePlatformSendAllPartiesDomainEvent(event: PlatformSendAllPartiesDomainEvent) {
        sendAllPartiesToNewlyConnectedParty(event.platform, event.partyId, event.countryCode)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HubClientInfoListener::class.java)
    }

    /** Sends out ClientInfo updates to locally connected parties and nodes on network */
    private fun notifyNetworkOfChanges(
            changedPlatform: PlatformEntity,
            changedRoles: Iterable<RoleEntity>
    ) {
        for (platformRole in changedRoles) {
            val updatedClientInfo =
                    ClientInfo(
                            partyID = platformRole.partyID,
                            countryCode = platformRole.countryCode,
                            role = platformRole.role,
                            status = changedPlatform.status,
                            lastUpdated = changedPlatform.lastUpdated
                    )

            val parties =
                    hubClientInfoService.getPartiesToNotifyOfClientInfoChange(
                            changedPlatform,
                            updatedClientInfo
                    )

            hubClientInfoService.updateClientInfo(updatedClientInfo);
            hubClientInfoService.notifyPartiesOfClientInfoChange(parties, updatedClientInfo)
        }
    }

    /**
     * Sends all connected parties to a newly connected party if it supports the HubClientInfo module.
     * This method checks if the newly connected platform supports HubClientInfo and sends all existing
     * connected parties to it.
     */
    private fun sendAllPartiesToNewlyConnectedParty(newlyConnectedPlatform: PlatformEntity, partyId: String, countryCode:  String) {
            val allRegisteredParties = hubClientInfoService.getAllRegisteredParties();
            for (clientInfo in allRegisteredParties) {
                try {
                    val tokenB = newlyConnectedPlatform.auth.tokenB;

                    if(tokenB != null) {
                        hubClientInfoService.notifyPartyOfClientInfoChange(partyId, countryCode, tokenB, clientInfo)
                    }
                } catch (e: Exception) {
                    // Log error but continue with other parties
                    logger.warn("Error sending client info ${clientInfo.partyID} to newly connected platform ${newlyConnectedPlatform.id}: ${e.message}")
                }
            }
    }

    private fun notifyNetworkOfRoleStatusChange(
            role: NetworkClientInfoEntity,
            status: ConnectionStatus
    ) {
        val clientInfo =
                ClientInfo(
                        partyID = role.party.id,
                        countryCode = role.party.country,
                        role = role.role,
                        status = status,
                        lastUpdated = role.lastUpdated
                )
        val parties =
                hubClientInfoService.getPartiesToNotifyOfClientInfoChange(clientInfo = clientInfo)
        hubClientInfoService.notifyPartiesOfClientInfoChange(parties, clientInfo)
        hubClientInfoService.updateClientInfo(clientInfo);
        hubClientInfoService.notifyNodesOfClientInfoChange(clientInfo)
    }
}
