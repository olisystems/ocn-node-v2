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

import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.EndpointEntity
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Credentials
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.RegistrationInfo
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.services.CredentialsService
import snc.openchargingnetwork.node.tools.fromBs64String
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
import snc.openchargingnetwork.node.tools.getTimestamp
import snc.openchargingnetwork.node.tools.toBs64String
import snc.openchargingnetwork.node.tools.urlJoin

data class PlatformAuthDto(
        val tokenA: String?,
        val tokenB: String?,
        val tokenC: String?,
        val selfCredentialsToken: String?,
        val handshakeSelfInitiated: Boolean
)

data class PlatformRoleDto(
        val id: Long?,
        val platformID: Long,
        val role: String,
        val partyID: String,
        val countryCode: String
)

data class PlatformDto(
        val id: Long?,
        val status: String,
        val lastUpdated: String,
        val versionsUrl: String?,
        val auth: PlatformAuthDto
)

data class PlatformWithRolesResponse(val platform: PlatformDto, val roles: List<PlatformRoleDto>)

data class CreatePlatformRequest(
        val tokenA: String? = null,
        val handshakeSelfInitiated: Boolean = false,
        val platformVersionsUrl: String? = null
)

private val logger = LoggerFactory.getLogger(AdminController::class.java)

@RestController
@RequestMapping("\${ocn.node.apiPrefix}/admin")
class AdminController(
        private val platformRepo: PlatformRepository,
        private val roleRepo: RoleRepository,
        private val endpointRepo: EndpointRepository,
        private val properties: NodeProperties,
        private val ocnRegistryComponent: OcnRegistryComponent,
        private val httpClientComponent: HttpClientComponent,
        private val credentialsService: CredentialsService
) {

    fun isAuthorized(authorization: String): Boolean {
        return authorization == "Token ${properties.apikey}" ||
                authorization == "Token ${properties.apikey.toBs64String()}"
    }

    @PostMapping("/platform/{platformId}/start-handshake")
    @Transactional
    fun startHandshake(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable platformId: Long
    ): ResponseEntity<String> {
        // Log request
        logger.info("Starting handshake for platform: $platformId")

        // Check authorization
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid Authorization")
        }

        // Get the platform
        val platform =
                platformRepo.findByIdOrNull(platformId)
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body("Platform not found")

        // Verify platform was created with handshakeSelfInitiated=true
        if (!platform.auth.handshakeSelfInitiated) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Platform was not created with handshakeSelfInitiated=true")
        }

        // Verify versionsUrl is set
        val versionsUrl =
                platform.versionsUrl
                        ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body("Platform has no versionsUrl set")

        // Get tokenA
        val tokenA =
                platform.auth.tokenA
                        ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body("Platform has no tokenA")

        try {
            // Step 1: Call platform-whitelabel's versions endpoint with tokenA
            val versions = httpClientComponent.getVersions(versionsUrl, tokenA.fromBs64String())

            // Step 2: Find matching version (2.2.1 or 2.2)
            val targetVersion =
                    versions.firstOrNull { it.version == "2.2.1" || it.version == "2.2" }
                            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body("No compatible OCPI version found")

            // Step 3: Get version details
            val versionDetail =
                    httpClientComponent.getVersionDetail(targetVersion.url, tokenA.fromBs64String())

            // Step 4: Extract credentials module URL
            val credentialsEndpoint =
                    versionDetail.endpoints.find { it.identifier == "credentials" }
                            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body("No credentials endpoint found")

            // Step 5: Get existing tokenB (self_credentials_token)
            val tokenB =
                    platform.auth.selfCredentialsToken
                            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body("Platform has no selfCredentialsToken")

            // Step 6: Build credentials payload with tokenB
            val credentialsPayload = credentialsService.myCredentials(tokenB.fromBs64String())

            // Step 7: Call platform-whitelabel's credentials POST with tokenA in header, tokenB in
            // body
            val credentialsResponse =
                    httpClientComponent.makeOcpiRequest<Credentials>(
                            method = HttpMethod.POST,
                            url = credentialsEndpoint.url,
                            headers =
                                    mapOf(
                                            "Authorization" to "Token ${tokenA.fromBs64String()}",
                                            "Content-Type" to "application/json",
                                            "X-Request-ID" to generateUUIDv4Token(),
                                            "X-Correlation-ID" to generateUUIDv4Token()
                                    ),
                            body = httpClientComponent.mapper.writeValueAsString(credentialsPayload)
                    )

            // Check response
            if (credentialsResponse.body?.statusCode != 1000) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Credentials POST failed: ${credentialsResponse.body?.statusMessage}")
            }

            // Step 8: Extract tokenC from response
            val receivedCredentials =
                    credentialsResponse.body?.data
                            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body("No credentials data in response")
            val tokenCPlain = receivedCredentials.token
            val tokenC = receivedCredentials.token.toBs64String()

            // Step 9: Store tokenC in platform
            platform.auth =
                    Auth(
                            tokenA = platform.auth.tokenA,
                            tokenB = platform.auth.tokenB,
                            selfCredentialsToken = platform.auth.selfCredentialsToken,
                            handshakeSelfInitiated = platform.auth.handshakeSelfInitiated,
                            tokenC = tokenC
                    )

            // Step 10: Update platform.status to CONNECTED
            platform.status = ConnectionStatus.CONNECTED
            platform.lastUpdated = getTimestamp()
            platformRepo.save(platform)

            return ResponseEntity.status(HttpStatus.OK)
                    .body("Handshake completed successfully. Platform status: CONNECTED")
        } catch (e: Exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Handshake failed: ${e.message}")
        }
    }

    @GetMapping("/connection-status/{countryCode}/{partyID}")
    fun getConnectionStatus(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyID: String
    ): ResponseEntity<String> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body("Role not found in this node")

        val platform =
                platformRepo.findByIdOrNull(role.platformID)
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body("Role found but could not find connection status")

        return ResponseEntity.ok().body(platform.status.toString())
    }

    @PostMapping("/platform")
    @Transactional
    fun createPlatform(
            @RequestHeader("Authorization") authorization: String,
            @RequestBody body: CreatePlatformRequest
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(
                            OcpiResponse<Unit>(
                                    statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                                    statusMessage = "Invalid admin / api key"
                            )
                    )
        }

        // validate required fields when handshake is self-initiated
        if (body.handshakeSelfInitiated) {
            if (body.tokenA.isNullOrEmpty()) {
                return ResponseEntity.badRequest()
                        .body(
                                OcpiResponse<Unit>(
                                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                                        statusMessage =
                                                "tokenA is required when handshakeSelfInitiated is true"
                                )
                        )
            }
            if (body.platformVersionsUrl.isNullOrEmpty()) {
                return ResponseEntity.badRequest()
                        .body(
                                OcpiResponse<Unit>(
                                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                                        statusMessage =
                                                "platformVersionsUrl is required when handshakeSelfInitiated is true"
                                )
                        )
            }
        }

        val platform =
                if (body.handshakeSelfInitiated) {
                    // self-initiated handshake: use provided tokenA, generate selfCredentialsToken,
                    // store versionsUrl
                    val selfCredentialsToken = generateUUIDv4Token().toBs64String()
                    PlatformEntity(
                            auth =
                                    Auth(
                                            tokenA = body.tokenA!!.toBs64String(),
                                            tokenB = selfCredentialsToken,
                                            selfCredentialsToken = selfCredentialsToken,
                                            handshakeSelfInitiated = true
                                    ),
                            versionsUrl = body.platformVersionsUrl
                    )
                } else {
                    // generate and store new platform with authorization token
                    val tokenA = generateUUIDv4Token()
                    PlatformEntity(
                            auth =
                                    Auth(
                                            tokenA = tokenA.toBs64String(),
                                            selfCredentialsToken = tokenA.toBs64String(),
                                            handshakeSelfInitiated = false
                                    )
                    )
                }
        platformRepo.save(platform)

        return if (body.handshakeSelfInitiated) {
            // self-initiated handshake: return 201 with no body
            ResponseEntity.status(HttpStatus.CREATED).build()
        } else {
            // normal flow: return 201 with RegistrationInfo
            val responseBody =
                    RegistrationInfo(
                            platform.auth.tokenA!!.fromBs64String(),
                            urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions")
                    )
            ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
        }
    }

    @GetMapping("/platform-by-party/{countryCode}/{partyID}")
    fun getPlatform(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyID: String
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body("Role not found in this node")

        val platform =
                platformRepo.findByIdOrNull(role.platformID)
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body("Role found but could not find connection status")

        return ResponseEntity.ok().body(platform)
    }

    @GetMapping("/role/{countryCode}/{partyID}")
    fun getRole(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyID: String
    ): ResponseEntity<Any> {
        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()

        return ResponseEntity.ok().body(role)
    }

    @GetMapping("/endpoints/{countryCode}/{partyID}")
    fun getEndpoints(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyID: String
    ): ResponseEntity<Iterable<EndpointEntity>> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(null)
        }
        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()
                        ?: return ResponseEntity.ok().body(null)

        // if role exists platform is suposed to be there too, that is why the error message in this
        // case
        val platform =
                platformRepo.findByIdOrNull(role.platformID)
                        ?: return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null)

        val endpoints = endpointRepo.findByPlatformID(platform.id)

        return ResponseEntity.ok().body(endpoints)
    }

    @DeleteMapping("/party/{countryCode}/{partyID}")
    @Transactional
    fun deleteParty(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable countryCode: String,
            @PathVariable partyID: String
    ): ResponseEntity<String> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        var responseMessage = "No role found for party $partyID in country $countryCode"
        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()
        if (role != null) {
            // resolve platform before deletions
            val platform = platformRepo.findByIdOrNull(role.platformID)

            // 1) delete endpoints (depend on platform)
            if (platform != null) {
                endpointRepo.deleteByPlatformID(platform.id)
                responseMessage = "Endpoints deleted successfully"
            }

            // 2) delete ALL roles for this platform (not just the matched one)
            roleRepo.deleteByPlatformID(role.platformID)
            responseMessage =
                    if (responseMessage.isBlank()) "Roles deleted successfully"
                    else "$responseMessage | Roles deleted successfully"

            // 3) delete platform (after all dependents removed)
            if (platform != null) {
                platformRepo.delete(platform)
                responseMessage += " | Platform deleted successfully"
            }
        }

        return ResponseEntity.ok().body(responseMessage)
    }

    @GetMapping("/platform/{platformId}")
    fun getPlatform(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable platformId: String
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val platform = platformRepo.findByIdOrNull(platformId.toLong())
        return if (platform != null) {
            ResponseEntity.ok().body(platform)
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body("Platform not found")
        }
    }

    @DeleteMapping("/platform/{platformId}")
    @Transactional
    fun deletePlatform(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable platformId: String
    ): ResponseEntity<String> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val platform = platformRepo.findByIdOrNull(platformId.toLong())
        return if (platform != null) {
            var responseMessage = ""

            // 1) delete endpoints (depend on platform)
            endpointRepo.deleteByPlatformID(platform.id)
            responseMessage = "Endpoints deleted successfully"

            // 2) delete ALL roles for this platform
            roleRepo.deleteByPlatformID(platform.id)
            responseMessage =
                    if (responseMessage.isBlank()) "Roles deleted successfully"
                    else "$responseMessage | Roles deleted successfully"

            // 3) delete platform (after all dependents removed)
            platformRepo.delete(platform)
            responseMessage += " | Platform deleted successfully"

            ResponseEntity.ok().body(responseMessage)
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body("Platform not found")
        }
    }

    @GetMapping("/platforms")
    fun getAllPlatforms(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Any> {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }
        val platforms =
                platformRepo.findAll().map { platform ->
                    PlatformWithRolesResponse(
                            platform =
                                    PlatformDto(
                                            id = platform.id,
                                            status = platform.status.name,
                                            lastUpdated = platform.lastUpdated,
                                            versionsUrl = platform.versionsUrl,
                                            auth =
                                                    PlatformAuthDto(
                                                            tokenA = platform.auth.tokenA,
                                                            tokenB = platform.auth.tokenB,
                                                            tokenC = platform.auth.tokenC,
                                                            selfCredentialsToken =
                                                                    platform.auth
                                                                            .selfCredentialsToken,
                                                            handshakeSelfInitiated =
                                                                    platform.auth
                                                                            .handshakeSelfInitiated
                                                    )
                                    ),
                            roles =
                                    roleRepo.findAllByPlatformID(platform.id).map { role ->
                                        PlatformRoleDto(
                                                id = role.id,
                                                platformID = role.platformID,
                                                role = role.role.name,
                                                partyID = role.partyID,
                                                countryCode = role.countryCode
                                        )
                                    }
                    )
                }
        return ResponseEntity.ok().body(platforms)
    }

    @PostMapping("/refresh-blockchain")
    fun refreshBlockchain(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<String> {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized")
        }
        return try {
            ocnRegistryComponent.getRegistry(true)
            ResponseEntity.ok("Registry refreshed from subgraph")
        } catch (ex: Exception) {
            ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("Failed to refresh registry: ${ex.message}")
        }
    }
}
