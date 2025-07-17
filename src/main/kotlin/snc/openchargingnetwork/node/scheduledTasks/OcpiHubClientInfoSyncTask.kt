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

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import snc.openchargingnetwork.node.services.HubClientInfoService

/**
 * Scheduled task for comprehensive OCPI Hub Client Info synchronization This task performs both
 * pull and push operations to keep hub client info up to date
 */
@Component
class OcpiHubClientInfoSyncTask(private val hubClientInfoService: HubClientInfoService) : Runnable {

    companion object {
        private val logger = LoggerFactory.getLogger(OcpiHubClientInfoSyncTask::class.java)
    }

    override fun run() {
        try {
            logger.info("Starting scheduled OCPI Hub Client Info sync task...")
            hubClientInfoService.syncHubClientInfo()
            logger.info("Scheduled OCPI Hub Client Info sync task completed successfully")
        } catch (e: Exception) {
            logger.error("Error during scheduled OCPI Hub Client Info sync task: ${e.message}", e)
        }
    }
}
