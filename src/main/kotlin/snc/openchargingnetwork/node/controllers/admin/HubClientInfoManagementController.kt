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

package snc.openchargingnetwork.node.controllers.admin

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.services.HubClientInfoService

/**
 * Admin controller for managing OCPI Hub Client Info operations Provides endpoints for manual
 * triggering of sync operations and monitoring Uses event-driven architecture for broadcasting
 */
@RestController
@RequestMapping("\${ocn.node.apiPrefix}/admin/hub-client-info")
class HubClientInfoManagementController(private val hubClientInfoService: HubClientInfoService) {

    /**
     * Manually trigger a comprehensive hub client info sync This performs both pull (checking
     * registry) and push (broadcasting) operations
     */
    @PostMapping("/sync")
    fun triggerHubClientInfoSync(): ResponseEntity<Map<String, String>> {
        return try {
            hubClientInfoService.syncHubClientInfo()
            ResponseEntity.ok(
                    mapOf(
                            "status" to "success",
                            "message" to "Hub client info sync completed successfully"
                    )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(
                            mapOf<String, String>(
                                    "status" to "error",
                                    "message" to (e.message ?: "Unknown error")
                            )
                    )
        }
    }

    /** Manually check for new parties from the registry (PULL operation) */
    @PostMapping("/check-new-parties")
    fun checkForNewParties(): ResponseEntity<Map<String, String>> {
        return try {
            val indexedParties = hubClientInfoService.getIndexedParties()
            hubClientInfoService.checkForNewPartiesFromRegistry(indexedParties)
            ResponseEntity.ok(
                    mapOf(
                            "status" to "success",
                            "message" to
                                    "New parties check completed - events will handle broadcasting"
                    )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(
                            mapOf<String, String>(
                                    "status" to "error",
                                    "message" to (e.message ?: "Unknown error")
                            )
                    )
        }
    }

    /** Manually check for suspended parties (PULL operation) */
    @PostMapping("/check-suspended-updates")
    fun checkForSuspendedUpdates(): ResponseEntity<Map<String, String>> {
        return try {
            val indexedParties = hubClientInfoService.getIndexedParties()
            hubClientInfoService.checkForSuspendedUpdates(indexedParties)
            ResponseEntity.ok(
                    mapOf<String, String>(
                            "status" to "success",
                            "message" to
                                    "Suspended parties check completed - events will handle broadcasting"
                    )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(
                            mapOf<String, String>(
                                    "status" to "error",
                                    "message" to (e.message ?: "Unknown error")
                            )
                    )
        }
    }

    @PostMapping("/broadcast")
    fun broadcastHubClientInfo(
            @RequestBody clientInfo: ClientInfo
    ): ResponseEntity<Map<String, String>> {
        return try {
            // Use event-driven approach - saving triggers the event system
            hubClientInfoService.saveClientInfo(clientInfo)
            ResponseEntity.ok(
                    mapOf(
                            "status" to "success",
                            "message" to "Hub client info broadcast completed via events"
                    )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(
                            mapOf<String, String>(
                                    "status" to "error",
                                    "message" to (e.message ?: "Unknown error")
                            )
                    )
        }
    }

    /** Renew client connection for a specific party */
    @PostMapping("/renew-connection")
    fun renewClientConnection(
            @RequestParam partyId: String,
            @RequestParam countryCode: String
    ): ResponseEntity<Map<String, String>> {
        return try {
            val basicRole = snc.openchargingnetwork.node.models.ocpi.BasicRole(partyId, countryCode)
            hubClientInfoService.renewClientConnection(basicRole)
            ResponseEntity.ok(
                    mapOf(
                            "status" to "success",
                            "message" to "Client connection renewed successfully"
                    )
            )
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(
                            mapOf<String, String>(
                                    "status" to "error",
                                    "message" to (e.message ?: "Unknown error")
                            )
                    )
        }
    }
}
