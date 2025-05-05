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
import org.springframework.scheduling.config.IntervalTask
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.repositories.*
import snc.openchargingnetwork.node.scheduledTasks.HubClientInfoStillAliveCheck
import snc.openchargingnetwork.node.scheduledTasks.PlannedPartySearch
import snc.openchargingnetwork.node.services.HttpService as OcnHttpService


@Configuration
class NodeConfig(private val properties: NodeProperties) {

    @Bean
    fun databaseInitializer(platformRepo: PlatformRepository,
                            roleRepo: RoleRepository,
                            endpointRepo: EndpointRepository,
                            proxyResourceRepository: ProxyResourceRepository) = ApplicationRunner {}


    // TODO: Use the indexer instead
    @Bean
    fun ocnRegistry(): OcnRegistry {
        return OcnRegistry(properties.registryIndexerUrl)
    }

    @Bean
    fun coroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    // TODO: Move away from deprecated method
    @Bean
    fun newScheduledTasks(registry: OcnRegistry,
                          httpService: OcnHttpService,
                          platformRepo: PlatformRepository,
                          roleRepo: RoleRepository,
                          networkClientInfoRepo: NetworkClientInfoRepository): List<IntervalTask> {

        val taskList = mutableListOf<IntervalTask>()
        val hasPrivateKey = properties.privateKey !== null

        if (properties.stillAliveEnabled && hasPrivateKey) {
            val stillAliveTask = HubClientInfoStillAliveCheck(httpService, platformRepo, properties)
//            val interval = properties.stillAliveRate.toLong().toDuration(DurationUnit.MILLISECONDS)
            taskList.add(IntervalTask(stillAliveTask, properties.stillAliveRate.toLong()))
        }

        //
        if (properties.plannedPartySearchEnabled && hasPrivateKey) {
            val plannedPartyTask = PlannedPartySearch(registry, roleRepo, networkClientInfoRepo, properties)
            taskList.add(IntervalTask(plannedPartyTask, properties.plannedPartySearchRate.toLong()))
        }
        return taskList.toList()
    }



//    // modify the default task executor (runs async tasks, not to be confused with scheduled tasks)
//    @Bean
//    fun taskExecutor(): TaskExecutor {
//        val taskExecutor = ThreadPoolTaskExecutor()
//        taskExecutor.corePoolSize = 100
//        taskExecutor.setThreadNamePrefix("worker-")
//        return taskExecutor
//    }

}

