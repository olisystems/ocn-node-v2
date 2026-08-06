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
import snc.openchargingnetwork.node.models.entities.Ocpi211AdapterConfigEntity
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Credentials
import snc.openchargingnetwork.node.models.ocpi.CredentialsRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.RegistrationInfo
import snc.openchargingnetwork.node.models.ocpi.RegistrationRoleRequest
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.Ocpi211AdapterConfigRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.services.CredentialsService
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
        val auth: PlatformAuthDto,
        val testTool: Boolean = false
)

data class PlatformWithRolesResponse(val platform: PlatformDto, val roles: List<PlatformRoleDto>)

data class CreatePlatformRequest(
        val tokenA: String? = null,
        val handshakeSelfInitiated: Boolean = false,
        val platformVersionsUrl: String? = null,
        val testTool: Boolean = false
)

private val logger = LoggerFactory.getLogger(AdminController::class.java)

@RestController
@RequestMapping("\${ocn.node.apiPrefix}/admin")
class AdminController(
        private val platformRepo: PlatformRepository,
        private val roleRepo: RoleRepository,
        private val endpointRepo: EndpointRepository,
        private val adapterConfigRepo: Ocpi211AdapterConfigRepository,
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
    ): ResponseEntity<Any> {
        logger.info("[Handshake] platformId={} step=request_received", platformId)

        // Check authorization
        if (!isAuthorized(authorization)) {
            logger.warn(
                    "[Handshake] platformId={} step=validate_request failed: invalid admin authorization",
                    platformId
            )
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))
        }

        // Get the platform
        val platform =
                platformRepo.findByIdOrNull(platformId)
                        ?: run {
                            logger.warn(
                                    "[Handshake] platformId={} step=load_platform failed: platform not found",
                                    platformId
                            )
                            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                    .body(mapOf("error" to "Platform not found"))
                        }

        // Verify platform was created with handshakeSelfInitiated=true
        if (!platform.auth.handshakeSelfInitiated) {
            logger.warn(
                    "[Handshake] platformId={} step=validate_platform failed: handshakeSelfInitiated is false",
                    platformId
            )
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "Platform was not created with handshakeSelfInitiated=true"))
        }

        // Verify versionsUrl is set
        val versionsUrl =
                platform.versionsUrl
                        ?: run {
                            logger.warn(
                                    "[Handshake] platformId={} step=validate_platform failed: versions URL is missing",
                                    platformId
                            )
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(mapOf("error" to "Platform has no versionsUrl set"))
                        }

        // Get tokenA
        val tokenA =
                platform.auth.tokenA
                        ?: run {
                            logger.warn(
                                    "[Handshake] platformId={} step=validate_platform failed: Token A is missing",
                                    platformId
                            )
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(mapOf("error" to "Platform has no tokenA"))
                        }

        logger.info(
                "[Handshake] platformId={} step=validate_platform completed versionsUrl={}",
                platformId,
                versionsUrl
        )

        var currentStep = "fetch_versions"
        var requestId: String? = null
        var correlationId: String? = null
        try {
            // Step 1: Call platform-whitelabel's versions endpoint with tokenA
            logger.info(
                    "[Handshake] platformId={} step={} url={}",
                    platformId,
                    currentStep,
                    versionsUrl
            )
            val versions = httpClientComponent.getVersions(versionsUrl, tokenA)
            logger.info(
                    "[Handshake] platformId={} step={} completed supportedVersions={}",
                    platformId,
                    currentStep,
                    versions.map { it.version }
            )

            // Step 2: Find matching version (2.2.1 or 2.2)
            currentStep = "select_version"
            val targetVersion =
                    versions.firstOrNull { it.version == "2.2.1" || it.version == "2.2" }
                            ?: run {
                                logger.warn(
                                        "[Handshake] platformId={} step={} failed: no compatible OCPI version",
                                        platformId,
                                        currentStep
                                )
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(mapOf("error" to "No compatible OCPI version found"))
                            }
            logger.info(
                    "[Handshake] platformId={} step={} completed version={} url={}",
                    platformId,
                    currentStep,
                    targetVersion.version,
                    targetVersion.url
            )

            // Step 3: Get version details
            currentStep = "fetch_version_details"
            logger.info(
                    "[Handshake] platformId={} step={} url={}",
                    platformId,
                    currentStep,
                    targetVersion.url
            )
            val versionDetail =
                    httpClientComponent.getVersionDetail(targetVersion.url, tokenA)
            logger.info(
                    "[Handshake] platformId={} step={} completed endpointCount={}",
                    platformId,
                    currentStep,
                    versionDetail.endpoints.size
            )

            // Step 4: Extract credentials module URL (RECEIVER, same as update/verify flows)
            currentStep = "select_credentials_endpoint"
            val credentialsEndpoint =
                    versionDetail.endpoints.find {
                        it.identifier == "credentials" && it.role == InterfaceRole.RECEIVER
                    }
                            ?: run {
                                logger.warn(
                                        "[Handshake] platformId={} step={} failed: credentials RECEIVER endpoint is missing",
                                        platformId,
                                        currentStep
                                )
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(
                                                mapOf(
                                                        "error" to
                                                                "No credentials RECEIVER endpoint found"
                                                )
                                        )
                            }
            logger.info(
                    "[Handshake] platformId={} step={} completed url={}",
                    platformId,
                    currentStep,
                    credentialsEndpoint.url
            )

            // Step 5: Get existing tokenB (self_credentials_token)
            currentStep = "prepare_credentials"
            val tokenB =
                    platform.auth.selfCredentialsToken
                            ?: run {
                                logger.warn(
                                        "[Handshake] platformId={} step={} failed: self credentials token is missing",
                                        platformId,
                                        currentStep
                                )
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(mapOf("error" to "Platform has no selfCredentialsToken"))
                            }

            // Step 6: Build credentials payload with tokenB
            val credentialsPayload = credentialsService.myCredentials(tokenB)
            logger.info(
                    "[Handshake] platformId={} step={} completed callbackUrl={} roles={}",
                    platformId,
                    currentStep,
                    credentialsPayload.url,
                    credentialsPayload.roles.map {
                        "${it.countryCode}-${it.partyID}:${it.role}"
                    }
            )

            // Step 7: Call platform-whitelabel's credentials POST with tokenA in
            // header, tokenB in
            // body
            currentStep = "post_credentials"
            requestId = generateUUIDv4Token()
            correlationId = generateUUIDv4Token()
            logger.info(
                    "[Handshake] platformId={} step={} url={} requestId={} correlationId={}",
                    platformId,
                    currentStep,
                    credentialsEndpoint.url,
                    requestId,
                    correlationId
            )
            val credentialsResponse =
                    httpClientComponent.makeOcpiRequest<Credentials>(
                            method = HttpMethod.POST,
                            url = credentialsEndpoint.url,
                            headers =
                                    mapOf(
                                            "Authorization" to "Token ${tokenA.toBs64String()}",
                                            "Content-Type" to "application/json",
                                            "X-Request-ID" to requestId,
                                            "X-Correlation-ID" to correlationId
                                    ),
                            body =
                                    httpClientComponent.mapper.writeValueAsString(
                                            credentialsPayload
                                    ),
                            typeClass = Credentials::class.java
                    )
            logger.info(
                    "[Handshake] platformId={} step={} completed httpStatus={} ocpiStatus={} requestId={} correlationId={}",
                    platformId,
                    currentStep,
                    credentialsResponse.statusCode,
                    credentialsResponse.body?.statusCode,
                    requestId,
                    correlationId
            )

            // Check response
            if (credentialsResponse.body?.statusCode != 1000) {
                logger.warn(
                        "[Handshake] platformId={} step={} failed: OCPI status={} message={}",
                        platformId,
                        currentStep,
                        credentialsResponse.body?.statusCode,
                        credentialsResponse.body?.statusMessage
                )
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(mapOf("error" to "Credentials POST failed: ${credentialsResponse.body?.statusMessage}"))
            }

            // Step 8: Extract tokenC from response
            currentStep = "validate_credentials_response"
            val receivedCredentials =
                    credentialsResponse.body?.data
                            ?: run {
                                logger.warn(
                                        "[Handshake] platformId={} step={} failed: response has no credentials data",
                                        platformId,
                                        currentStep
                                )
                                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                        .body(mapOf("error" to "No credentials data in response"))
                            }
            val tokenCPlain = receivedCredentials.token
            logger.info(
                    "[Handshake] platformId={} step={} completed remoteUrl={} roles={} tokenReceived=true",
                    platformId,
                    currentStep,
                    receivedCredentials.url,
                    receivedCredentials.roles.map {
                        "${it.countryCode}-${it.partyID}:${it.role}"
                    }
            )

            // Step 9: Store tokenC in platform (plain text)
            currentStep = "persist_platform"
            platform.auth =
                    Auth(
                            tokenA = platform.auth.tokenA,
                            tokenB = platform.auth.tokenB,
                            selfCredentialsToken = platform.auth.selfCredentialsToken,
                            handshakeSelfInitiated = platform.auth.handshakeSelfInitiated,
                            tokenC = tokenCPlain
                    )

            // Step 10: Update platform.status to CONNECTED
            platform.status = ConnectionStatus.CONNECTED
            platform.lastUpdated = getTimestamp()
            platformRepo.save(platform)
            logger.info(
                    "[Handshake] platformId={} step={} completed status={}",
                    platformId,
                    currentStep,
                    platform.status
            )

            // Step 11: Save roles from credentials response
            currentStep = "persist_roles"
            // First, delete any existing roles with same (country_code, party_id, role) to prevent duplicates
            receivedCredentials.roles.forEach { role ->
                roleRepo.deleteByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
                        role.countryCode,
                        role.partyID,
                        role.role
                )
            }
            roleRepo.flush()

            val roles =
                    receivedCredentials.roles.map { role: CredentialsRole ->
                        RoleEntity(
                                platformID = platform.id!!,
                                role = role.role,
                                businessDetails = role.businessDetails,
                                partyID = role.partyID,
                                countryCode = role.countryCode
                        )
                    }
            roleRepo.saveAll(roles)
            logger.info(
                    "[Handshake] platformId={} step={} completed roleCount={}",
                    platformId,
                    currentStep,
                    roles.size
            )

            // Step 12: Save endpoints from version details
            currentStep = "persist_endpoints"
            for (endpoint in versionDetail.endpoints) {
                endpointRepo.save(
                        EndpointEntity(
                                platformID = platform.id!!,
                                identifier = endpoint.identifier,
                                role = endpoint.role,
                                url = endpoint.url
                        )
                    )
            }
            logger.info(
                    "[Handshake] platformId={} step={} completed endpointCount={}",
                    platformId,
                    currentStep,
                    versionDetail.endpoints.size
            )

            logger.info(
                    "[Handshake] platformId={} step=complete status={}",
                    platformId,
                    platform.status
            )

            return ResponseEntity.status(HttpStatus.OK)
                    .body(mapOf(
                            "message" to "Handshake completed successfully. Platform status: CONNECTED",
                            "token_c" to tokenCPlain,
                            "base64_token_c" to "Token ${tokenCPlain.toBs64String()}"
                    ))
        } catch (e: Exception) {
            logger.error(
                    "[Handshake] platformId=$platformId step=$currentStep requestId=$requestId correlationId=$correlationId failed: ${e.message}",
                    e
            )
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(mapOf("error" to "Handshake failed: ${e.message}"))
        }
    }

    @PostMapping("/platform/{platformId}/update-credentials")
    @Transactional
    fun updateCredentials(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable platformId: Long
    ): ResponseEntity<Any> {
        // Log request
        logger.info("Updating credentials for platform: $platformId")

        // Check authorization
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "Invalid Authorization"))
        }

        // Get the platform
        val platform =
                platformRepo.findByIdOrNull(platformId)
                        ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(mapOf("error" to "Platform not found"))

        // Verify platform was created with handshakeSelfInitiated=true
        if (!platform.auth.handshakeSelfInitiated) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "Platform was not created with handshakeSelfInitiated=true"))
        }

        // Verify tokenC exists (existing connection required)
        val tokenC =
                platform.auth.tokenC
                        ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(mapOf("error" to "Platform has no existing connection (tokenC not found)"))

        // Verify selfCredentialsToken (tokenB) exists
        val tokenB =
                platform.auth.selfCredentialsToken
                        ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(mapOf("error" to "Platform has no selfCredentialsToken (tokenB)"))

        try {
            // Get credentials endpoint URL from stored endpoints
            val credentialsEndpoint = endpointRepo.findFirstByPlatformIDAndIdentifierAndRoleOrderByIdAsc(
                    platform.id,
                    "credentials",
                    InterfaceRole.RECEIVER
            ) ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "No credentials endpoint found for platform"))

            // Build credentials payload with tokenB
            val credentialsPayload = credentialsService.myCredentials(tokenB)

            // Call platform-whitelabel's PUT credentials endpoint
            val credentialsResponse =
                    httpClientComponent.makeOcpiRequest<Credentials>(
                            method = HttpMethod.PUT,
                            url = credentialsEndpoint.url,
                            headers =
                                    mapOf(
                                            "Authorization" to "Token ${tokenC.toBs64String()}",
                                            "Content-Type" to "application/json",
                                            "X-Request-ID" to generateUUIDv4Token(),
                                            "X-Correlation-ID" to generateUUIDv4Token()
                                    ),
                            body =
                                    httpClientComponent.mapper.writeValueAsString(
                                            credentialsPayload
                                    ),
                            typeClass = Credentials::class.java
                    )

            // Check response
            if (credentialsResponse.body?.statusCode != 1000) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(mapOf("error" to "Credentials PUT failed: ${credentialsResponse.body?.statusMessage}"))
            }

            // Extract NEW tokenC from response
            val receivedCredentials =
                    credentialsResponse.body?.data
                            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                    .body(mapOf("error" to "No credentials data in response"))
            val newTokenCPlain = receivedCredentials.token

            // Update platform with new tokenC (plain text)
            platform.auth =
                    Auth(
                            tokenA = platform.auth.tokenA,
                            tokenB = platform.auth.tokenB,
                            selfCredentialsToken = platform.auth.selfCredentialsToken,
                            handshakeSelfInitiated = platform.auth.handshakeSelfInitiated,
                            tokenC = newTokenCPlain
                    )
            platform.lastUpdated = getTimestamp()
            platformRepo.save(platform)

            // Save roles from credentials response
            // First, delete any existing roles with same (country_code, party_id, role) to prevent duplicates
            receivedCredentials.roles.forEach { role ->
                roleRepo.deleteByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
                        role.countryCode,
                        role.partyID,
                        role.role
                )
            }
            roleRepo.flush()

            val roles =
                    receivedCredentials.roles.map { role: CredentialsRole ->
                        RoleEntity(
                                platformID = platform.id!!,
                                role = role.role,
                                businessDetails = role.businessDetails,
                                partyID = role.partyID,
                                countryCode = role.countryCode
                        )
                    }
            roleRepo.saveAll(roles)

            logger.info("Credentials updated successfully for platform: $platformId")

            return ResponseEntity.status(HttpStatus.OK)
                    .body(mapOf(
                            "message" to "Credentials updated successfully",
                            "token_c" to newTokenCPlain,
                            "base64_token_c" to "Token ${newTokenCPlain.toBs64String()}"
                    ))
        } catch (e: Exception) {
            logger.error("Error updating credentials for platform $platformId: ${e.message}")
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(mapOf("error" to "Credentials update failed: ${e.message}"))
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

    /**
     * Legacy registration-token flow used by OCPI 2.1.1 adapter onboarding. Prefer
     * [createPlatform] for new platform provisioning.
     */
    @PostMapping("/generate-registration-token")
    @Transactional
    fun generateRegistrationToken(
            @RequestHeader("Authorization") authorization: String,
            @RequestBody body: Array<RegistrationRoleRequest>
    ): ResponseEntity<Any> {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        for (role in body) {
            if (roleRepo.existsByCountryCodeAndPartyIDAllIgnoreCase(role.country, role.id)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Role ${role.country}-${role.id} already exists")
            }
        }

        val adapterRole = body.find { it.credentialsRole != null || it.interfaceRole != null }
        if (adapterRole?.credentialsRole != null && adapterRole.interfaceRole == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(
                            "interface_role is required when credentials_role is set for OCPI 2.1.1 adapter onboarding"
                    )
        }
        if (adapterRole?.interfaceRole != null && adapterRole.credentialsRole == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(
                            "credentials_role is required when interface_role is set for OCPI 2.1.1 adapter onboarding"
                    )
        }

        val tokenA = generateUUIDv4Token()
        val platform =
                platformRepo.save(
                        PlatformEntity(
                                auth =
                                        Auth(
                                                tokenA = tokenA,
                                                selfCredentialsToken = tokenA,
                                                handshakeSelfInitiated = false
                                        )
                        )
                )

        if (adapterRole?.credentialsRole != null && adapterRole.interfaceRole != null) {
            adapterConfigRepo.save(
                    Ocpi211AdapterConfigEntity(
                            platformId = platform.id!!,
                            credentialsRole = adapterRole.credentialsRole!!.name,
                            interfaceRole = adapterRole.interfaceRole!!.name
                    )
            )
        }

        val responseBody =
                RegistrationInfo(
                        id = platform.id!!,
                        token = tokenA,
                        versions = urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions")
                )
        return ResponseEntity.ok().body(responseBody)
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

        // If tokenA provided, check for existing platform
        if (!body.tokenA.isNullOrEmpty()) {
            val existingPlatform = platformRepo.findByAuth_TokenA(body.tokenA)
            if (existingPlatform != null) {
                // Check if platform is ACTIVE (CONNECTED with tokenC)
                if (existingPlatform.status == ConnectionStatus.CONNECTED &&
                        !existingPlatform.auth.tokenC.isNullOrEmpty()) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(OcpiResponse<Unit>(
                                    statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                                    statusMessage = "There is already an ACTIVE connection between ocn node and a platform with tokenA = ${body.tokenA}"
                            ))
                }
                // Delete old platform and all dependents
                deletePlatformWithDependents(existingPlatform)
            }
        }

        val platform =
                if (body.handshakeSelfInitiated) {
                    // self-initiated handshake: use provided tokenA, generate
                    // selfCredentialsToken,
                    // store versionsUrl
                    val selfCredentialsToken = generateUUIDv4Token()
                    PlatformEntity(
                            auth =
                                    Auth(
                                            tokenA = body.tokenA!!,
                                            tokenB = selfCredentialsToken,
                                            selfCredentialsToken = selfCredentialsToken,
                                            handshakeSelfInitiated = true
                                    ),
                            versionsUrl = body.platformVersionsUrl,
                            testTool = body.testTool
                    )
                } else {
                    // generate and store new platform with authorization token
                    val tokenA = generateUUIDv4Token()
                    PlatformEntity(
                            auth =
                                    Auth(
                                            tokenA = tokenA,
                                            selfCredentialsToken = tokenA,
                                            handshakeSelfInitiated = false
                                    ),
                            testTool = body.testTool
                    )
                }
        platformRepo.save(platform)

        return ResponseEntity.status(HttpStatus.CREATED).body(platform)
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

        // if role exists platform is suposed to be there too, that is why the error message
        // in this
        // case
        val platform =
                platformRepo.findByIdOrNull(role.platformID)
                        ?: return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(null)

        val endpoints = endpointRepo.findByPlatformID(platform.id)

        return ResponseEntity.ok().body(endpoints)
    }

    @DeleteMapping("/platform-by-party/{countryCode}/{partyID}")
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

        val role =
                roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID)
                        .firstOrNull()
                        ?: return ResponseEntity.ok()
                                .body("No role found for party $partyID in country $countryCode")

        val platform =
                platformRepo.findByIdOrNull(role.platformID)
                        ?: return ResponseEntity.ok()
                                .body(
                                        "No platform found for party $partyID in country $countryCode"
                                )

        return ResponseEntity.ok().body(deletePlatformWithDependents(platform))
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
            ResponseEntity.ok().body(deletePlatformWithDependents(platform))
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND).body("Platform not found")
        }
    }

    @PostMapping("/platform/{platformId}/verify-credentials")
    fun verifyPlatformCredentials(
            @RequestHeader("Authorization") authorization: String,
            @PathVariable platformId: String
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val platform = platformRepo.findByIdOrNull(platformId.toLong())
                ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Platform not found")

        return try {
            // Get the auth token to use for the request
            val authToken = platform.getAuthTokenToIncludeInRequestHeader()

            // Find the credentials endpoint for this platform
            val credentialsEndpoint = endpointRepo.findFirstByPlatformIDAndIdentifierAndRoleOrderByIdAsc(
                    platform.id,
                    "credentials",
                    InterfaceRole.RECEIVER
            )

            if (credentialsEndpoint == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body("Credentials endpoint not found for platform")
            }

            // Build the full credentials URL
            val credentialsUrl = urlJoin(credentialsEndpoint.url)

            logger.info("Verifying credentials for platform ${platform.id} at URL: $credentialsUrl")

            // Make GET request to platform's credentials endpoint
            val headers = mapOf(
                    "Authorization" to "Token ${authToken.toBs64String()}",
                    "X-Correlation-ID" to generateUUIDv4Token(),
                    "X-Request-ID" to generateUUIDv4Token()
            )


            val response = httpClientComponent.sendHttpRequest(
                    endpoint = credentialsUrl,
                    method = HttpMethod.GET,
                    headers = headers
            )

            if (response.statusCode.value == 200) {
                logger.info("Credentials verification successful for platform ${platform.id}")
                ResponseEntity.ok().body(mapOf(
                        "message" to "Credentials verified successfully",
                        "platformId" to platform.id,
                        "credentialsUrl" to credentialsUrl,
                        "responseStatus" to response.statusCode.value,
                        "responseBody" to response.body
                ))
            } else {
                logger.warn("Credentials verification failed for platform ${platform.id}: HTTP ${response.statusCode}")
                ResponseEntity.status(response.statusCode.value).body(mapOf(
                        "message" to "Credentials verification failed",
                        "platformId" to platform.id,
                        "credentialsUrl" to credentialsUrl,
                        "responseStatus" to response.statusCode.value,
                        "responseBody" to response.body
                ))
            }

        } catch (e: Exception) {
            logger.error("Error verifying credentials for platform ${platform.id}: ${e.message}")
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapOf(
                    "message" to "Error verifying credentials",
                    "error" to e.message
            ))
        }
    }

    @DeleteMapping("/unused-platforms")
    @Transactional
    fun deleteUnusedPlatforms(
            @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val unusedPlatforms = platformRepo.findAll().filter { platform ->
            roleRepo.findAllByPlatformID(platform.id).none()
        }

        if (unusedPlatforms.isEmpty()) {
            return ResponseEntity.ok().body("No unused platforms found")
        }

        val deleted = unusedPlatforms.map { platform ->
            val summary = deletePlatformWithDependents(platform)
            mapOf("platformId" to platform.id, "summary" to summary)
        }

        return ResponseEntity.ok().body(
                mapOf(
                        "deletedCount" to deleted.size,
                        "platforms" to deleted
                )
        )
    }

    private fun deletePlatformWithDependents(platform: PlatformEntity): String {
        val platformId = platform.id
        // 1) delete endpoints (depend on platform)
        endpointRepo.deleteByPlatformID(platformId)

        // 2) delete adapter config if present (OCPI 2.1.1 plugin onboarding)
        if (platformId != null) {
            adapterConfigRepo.deleteByPlatformId(platformId)
        }

        // 3) delete all roles for this platform
        roleRepo.deleteByPlatformID(platformId)

        // 4) delete platform (after all dependents removed)
        platformRepo.delete(platform)

        return "Platform $platformId deleted (endpoints + roles + adapter config removed)"
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
                                                    ),
                                            testTool = platform.testTool
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
