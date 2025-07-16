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
import snc.openchargingnetwork.node.components.HttpClientComponent
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

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Component
    class ScheduledTasks(
            private val httpClientComponent: HttpClientComponent,
            private val platformRepo: PlatformRepository,
            private val properties: NodeProperties,
            private val hubClientInfoService: HubClientInfoService
    ) {

         @Scheduled(fixedRateString = "\${ocn.node.stillAliveRate}")
         fun runStillAliveCheck() {
             if (properties.stillAliveEnabled) {
                 val stillAliveTask =
                         HubClientInfoStillAliveCheck(httpClientComponent, platformRepo)
                 stillAliveTask.run()
             }
         }

        @Scheduled(fixedRateString = "\${ocn.node.hubClientInfoSyncRate}")
        fun runHubClientInfoSync() {
            if (properties.hubClientInfoSyncEnabled) {
                val hubClientInfoSyncTask = OcpiHubClientInfoSyncTask(hubClientInfoService)
                hubClientInfoSyncTask.run()
            }
        }
    }
}
