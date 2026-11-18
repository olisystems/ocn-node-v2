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

import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
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
import snc.openchargingnetwork.node.services.IntegrationCopyOutboxService
import snc.openchargingnetwork.node.services.StillAliveService

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
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @Component
    class ScheduledTasks(
            private val httpClientComponent: HttpClientComponent,
            private val platformRepo: PlatformRepository,
            private val properties: NodeProperties,
            private val hubClientInfoService: HubClientInfoService,
            private val stillAliveService: StillAliveService,
            private val integrationsProperties: IntegrationsProperties,
            private val integrationCopyOutboxService: IntegrationCopyOutboxService
    ) {

        private val logger = LoggerFactory.getLogger(ScheduledTasks::class.java)

         @Scheduled(fixedRateString = "\${ocn.node.stillAliveRate}")
         fun runStillAliveCheck() {
             if (properties.stillAliveEnabled) {
                 val stillAliveTask = HubClientInfoStillAliveCheck(stillAliveService)
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

        // fixedDelay: a run that is still sending must finish before the next one starts.
        @Scheduled(fixedDelayString = "\${ocn.integrations.outbox.pollMillis:5000}")
        fun runIntegrationCopyOutbox() {
            if (integrationsProperties.outbox.workerEnabled) {
                try {
                    integrationCopyOutboxService.dispatchDue()
                } catch (e: Exception) {
                    logger.error("Error during scheduled integration copy dispatch: ${e.message}", e)
                }
            }
        }

        @Scheduled(fixedDelayString = "\${ocn.integrations.outbox.cleanupMillis:3600000}")
        fun runIntegrationCopyOutboxCleanup() {
            if (integrationsProperties.outbox.workerEnabled) {
                try {
                    val deleted = integrationCopyOutboxService.purgeDelivered()
                    if (deleted > 0) {
                        logger.info("Deleted {} delivered integration copies from the outbox", deleted)
                    }
                } catch (e: Exception) {
                    logger.error("Error during integration copy outbox cleanup: ${e.message}", e)
                }
            }
        }
    }
}
