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
import snc.openchargingnetwork.node.services.StillAliveService

/**
 * Scheduled task checking whether the platforms registered on this node are still reachable. Only
 * platforms that haven't been heard from within the configured still alive rate are pinged; use
 * [StillAliveService.checkAllPlatforms] to check every platform on demand.
 */
class HubClientInfoStillAliveCheck(private val stillAliveService: StillAliveService) : Runnable {

    companion object {
        private val logger = LoggerFactory.getLogger(HubClientInfoStillAliveCheck::class.java)
    }

    override fun run() {
        try {
            stillAliveService.checkStalePlatforms()
        } catch (e: Exception) {
            logger.error("Error during scheduled still alive check: ${e.message}", e)
        }
    }
}
