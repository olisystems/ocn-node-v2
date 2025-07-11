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

package snc.openchargingnetwork.node.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.repositories.*
import snc.openchargingnetwork.node.scheduledTasks.HubClientInfoStillAliveCheck
import snc.openchargingnetwork.node.scheduledTasks.OcpiHubClientInfoSyncTask
import snc.openchargingnetwork.node.services.HubClientInfoService

@Configuration
@EnableScheduling
class NodeBootstrap(
        private val properties: NodeProperties,
        private val registryIndexerProperties: RegistryIndexerProperties,
        private val httpClientComponent: HttpClientComponent
) {

    @Bean
    fun databaseInitializer(
            platformRepo: PlatformRepository,
            roleRepo: RoleRepository,
            endpointRepo: EndpointRepository,
            proxyResourceRepository: ProxyResourceRepository
    ) = ApplicationRunner {}

    // TODO: Use the indexer instead
    @Bean
    fun ocnRegistry(): OcnRegistry {
        return try {
            // Fetch both operators and parties in parallel
            val (operatorsResponse, partiesResponse) =
                    httpClientComponent.getIndexedOcnRegistryOperatorsAndParties(
                            registryIndexerProperties.url,
                            registryIndexerProperties.token,
                            registryIndexerProperties.operatorsQuery,
                            registryIndexerProperties.partiesQuery
                    )

            val operators =
                    if (operatorsResponse.success) {
                        operatorsResponse.data?.operators ?: emptyList()
                    } else {
                        println("Warning: Failed to fetch operators: ${operatorsResponse.error}")
                        emptyList()
                    }

            val parties =
                    if (partiesResponse.success) {
                        partiesResponse.data?.parties ?: emptyList()
                    } else {
                        println("Warning: Failed to fetch parties: ${partiesResponse.error}")
                        emptyList()
                    }

            // Merge operators with complete party information
            val enrichedOperators =
                    operators.map { operator ->
                        // Find all parties that belong to this operator
                        val operatorParties =
                                parties.filter { party -> party.operator.id == operator.id }

                        // Create new operator with complete party information
                        operator.copy(parties = operatorParties)
                    }

            OcnRegistry(
                    url = registryIndexerProperties.url,
                    operators = enrichedOperators,
                    parties = parties
            )
        } catch (e: Exception) {
            println("Error initializing OCN Registry: ${e.message}")
            // Return empty registry as fallback
            OcnRegistry(
                    url = registryIndexerProperties.url,
                    operators = emptyList(),
                    parties = emptyList()
            )
        }
    }

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Component
    class ScheduledTasks(
            private val httpClientComponent: HttpClientComponent,
            private val platformRepo: PlatformRepository,
            private val networkClientInfoRepo: NetworkClientInfoRepository,
            private val properties: NodeProperties,
            private val registryIndexerProperties: RegistryIndexerProperties,
            private val hubClientInfoService: HubClientInfoService
    ) {
        companion object {
            const val STILL_ALIVE_RATE: Long = 5000 // defaults to 15 minutes
            const val HUB_CLIENT_INFO_SYNC_RATE: Long =
                    10000 // defaults to 1 hour (same as old planned party search)
        }

         @Scheduled(fixedRate = STILL_ALIVE_RATE)
         fun runStillAliveCheck() {
             if (properties.stillAliveEnabled) {
                 val stillAliveTask =
                         HubClientInfoStillAliveCheck(httpClientComponent, platformRepo, properties)
                 stillAliveTask.run()
             }
         }

        @Scheduled(fixedRate = HUB_CLIENT_INFO_SYNC_RATE)
        fun runHubClientInfoSync() {
            if (properties.hubClientInfoSyncEnabled) {
                val hubClientInfoSyncTask = OcpiHubClientInfoSyncTask(hubClientInfoService)
                hubClientInfoSyncTask.run()
            }
        }
    }
}
