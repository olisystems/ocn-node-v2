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

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.services.AdminAuthorizationService
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.StillAliveService

/**
 * Admin controller for managing OCPI Hub Client Info operations Provides endpoints for manual
 * triggering of sync operations and monitoring Uses event-driven architecture for broadcasting
 */
@RestController
@RequestMapping("\${ocn.node.apiPrefix}\${ocn.node.apiPrefixPublic}/admin/hub-client-info")
class HubClientInfoManagementController(
        private val hubClientInfoService: HubClientInfoService,
        private val stillAliveService: StillAliveService,
        private val adminAuthorizationService: AdminAuthorizationService
) {

    private fun unauthorized(): ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))

    /**
     * List every hub client info record known to this node (local platform roles and network
     * parties), paginated with offset/limit.
     */
    @GetMapping
    fun getHubClientInfoList(
            @RequestHeader("Authorization") authorization: String,
            @RequestParam(required = false, defaultValue = "0") offset: Int,
            @RequestParam(required = false, defaultValue = "50") limit: Int
    ): ResponseEntity<Any> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))
        }

        return ResponseEntity.ok(hubClientInfoService.getPaginatedClientInfo(offset, limit))
    }

    /**
     * Manually trigger a comprehensive hub client info sync This performs both pull (checking
     * registry) and push (broadcasting) operations
     */
    @PostMapping("/sync")
    fun triggerHubClientInfoSync(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Map<String, String>> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return unauthorized()
        }

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
    fun checkForNewParties(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Map<String, String>> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return unauthorized()
        }

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
    fun checkForSuspendedUpdates(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Map<String, String>> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return unauthorized()
        }

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
            @RequestHeader("Authorization") authorization: String,
            @RequestBody clientInfo: ClientInfo
    ): ResponseEntity<Map<String, String>> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return unauthorized()
        }

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
            @RequestHeader("Authorization") authorization: String,
            @RequestParam partyId: String,
            @RequestParam countryCode: String
    ): ResponseEntity<Map<String, String>> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return unauthorized()
        }

        return try {
            val basicRole = BasicRole(partyId, countryCode)
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

    /**
     * Run a still alive check against every platform registered on this node, right away, instead
     * of waiting for the next scheduled run. Unreachable platforms - and every party role they
     * host - are moved to OFFLINE, reachable ones back to CONNECTED.
     */
    @PostMapping("/still-alive-check")
    fun runStillAliveCheckForAllParties(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Any> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))
        }

        return try {
            ResponseEntity.ok(stillAliveService.checkAllPlatforms())
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }

    /**
     * Run a still alive check for the platform the given party is registered on. Returns 404 only
     * when the party is not registered on this node - a network party known only from the registry
     * has no platform to ping. A party whose platform record is missing is inconsistent local
     * data, not an unknown party, so it falls through to the generic platform-failure response.
     */
    @PostMapping("/still-alive-check/{countryCode}/{partyId}")
    fun runStillAliveCheckForParty(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyId: String
    ): ResponseEntity<Any> {
        if (!adminAuthorizationService.isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))
        }

        return try {
            val party = BasicRole(id = partyId, country = countryCode)
            ResponseEntity.ok(stillAliveService.checkParty(party))
        } catch (e: IllegalArgumentException) {
            // only thrown when the party has no role on this node - see StillAliveService.checkParty
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(mapOf("status" to "error", "message" to (e.message ?: "Party not found")))
        } catch (e: Exception) {
            ResponseEntity.badRequest()
                    .body(mapOf("status" to "error", "message" to (e.message ?: "Unknown error")))
        }
    }
}
