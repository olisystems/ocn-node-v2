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

package snc.openchargingnetwork.node.scheduledTasks

import org.web3j.crypto.Credentials
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.NewRegistryPartyDetails
import snc.openchargingnetwork.node.models.entities.NetworkClientInfoEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.NetworkClientInfoRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.checksum


class PlannedPartySearch(
    private val ocnRegistryComponent: OcnRegistryComponent,
    private val networkClientInfoRepo: NetworkClientInfoRepository,
    private val roleRepository: RoleRepository,
    private val properties: NodeProperties
) : Runnable {

    override fun run() {

        val myAddress = Credentials.create(properties.privateKey).address.checksum()
        val registry = ocnRegistryComponent.getRegistry(forceReload = true)

        for (party in registry.parties) {
            NewRegistryPartyDetails(
                nodeOperator = party.operator.id,
                party = BasicRole(
                    country = party.countryCode,
                    id = party.partyId
                ),
                roles = party.roles
            )
        }
        val plannedParties = registry.parties
            .filter {
                val isMyParty = it.operator.id == myAddress
                isMyParty
            }
            .filter {
                val partyHasBeenDeleted = it.operator.id == "0x0000000000000000000000000000000000000000"
                !partyHasBeenDeleted
            }
            .filter {
                // Doing completed registration check after deleted party check as null countryCode and partyId
                // from deleted parties may cause an issue with query for postgresql
                val hasCompletedRegistration = roleRepository.existsByCountryCodeAndPartyIDAllIgnoreCase(
                    countryCode = it.countryCode,
                    partyID = it.partyId
                )
                !hasCompletedRegistration
            }

        for (party in plannedParties) {
            for (role in party.roles) {
                val partyBR = BasicRole(
                    country = party.countryCode,
                    id = party.partyId
                )
                if (!networkClientInfoRepo.existsByPartyAndRole(party = partyBR, role = role)) {
                    val networkClientInfo = NetworkClientInfoEntity(
                        party = partyBR,
                        role = role,
                        status = ConnectionStatus.PLANNED
                    )
                    networkClientInfo.foundNewlyPlannedRole()
                    networkClientInfoRepo.save(networkClientInfo)
                }
            }
        }
    }
}
