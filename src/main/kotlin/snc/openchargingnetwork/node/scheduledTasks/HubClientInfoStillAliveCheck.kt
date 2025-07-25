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

import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiServerUnusableApiException
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.tools.getInstant
import java.time.Instant


class HubClientInfoStillAliveCheck(
    private val properties: NodeProperties,
    private val httpClientComponent: HttpClientComponent,
    private val platformRepo: PlatformRepository
) : Runnable {

    override fun run() {
        val checkExecutionInstant = Instant.now()
        val lastUpdatedCutoff = checkExecutionInstant.minusMillis(properties.stillAliveRate)
        val clients = platformRepo.findByStatusIn(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE))
        for (client in clients) {
            updateClientStatus(client, lastUpdatedCutoff, checkExecutionInstant)
        }
    }

    /**
     * Update a client's connection status based on its availability if the status is stale
     * (i.e., if the platform hasn't been heard from in the set amount of time)
     */
    private fun updateClientStatus(client: PlatformEntity, lastUpdatedCutoff: Instant, newUpdatedTime: Instant) {
        val clientLastUpdated = getInstant(client.lastUpdated)
        if (clientLastUpdated < lastUpdatedCutoff) {
            val isConnected = isClientAvailable(client)
            if (isConnected) {
                client.renewConnection(newUpdatedTime)
                platformRepo.save(client)
            } else {
                client.disconnect(newUpdatedTime)
                platformRepo.save(client)
            }
        }
    }

    /**
     * Ping a platform's Versions endpoint to determine its availability.
     */
    private fun isClientAvailable(client: PlatformEntity): Boolean {
        try {
            if (client.versionsUrl == null || client.auth.tokenB == null) {
                return false // Client isn't configured. Assume not available
            }
            // If no exception thrown during version request, assume that request was successful
            httpClientComponent.getVersions(client.versionsUrl!!, client.auth.tokenB!!)
            return true
        } catch (e: OcpiServerUnusableApiException) {
            return false
        }
    }
}