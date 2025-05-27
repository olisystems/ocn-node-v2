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

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import snc.openchargingnetwork.node.config.HttpClientComponent
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.ControllerResponse
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.entities.NetworkClientInfoEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.NetworkClientInfoRepository


// TODO: Check if roles don't conflict, in case the Smart Contract doesn't.
class PlannedPartySearch(
    private val httpClientComponent: HttpClientComponent,
    private val networkClientInfoRepo: NetworkClientInfoRepository,
    private val registryIndexerProperties: RegistryIndexerProperties
) : Runnable {

    override fun run() {

        val response: ControllerResponse = httpClientComponent.getIndexedOcnRegistry(
            registryIndexerProperties.url,
            registryIndexerProperties.token,
            registryIndexerProperties.partiesQuery
        )
        if (!response.success) {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }

        for (party in response.data!!.parties!!) {
            for (role in party.roles) {
                val partyId = BasicRole(party.partyId, party.countryCode)
                if (!networkClientInfoRepo.existsByPartyAndRole(partyId, role)) {
                    val networkClientInfo = NetworkClientInfoEntity(
                        party = partyId.uppercase(),
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
