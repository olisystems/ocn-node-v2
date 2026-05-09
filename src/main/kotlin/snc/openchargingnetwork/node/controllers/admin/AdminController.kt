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

import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.EndpointEntity
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus
import snc.openchargingnetwork.node.models.ocpi.RegistrationInfo
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.fromBs64String
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
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

data class PlatformWithRolesResponse(
    val platform: PlatformDto,
    val roles: List<PlatformRoleDto>
)

data class CreatePlatformRequest(
    val roles: List<BasicRole>,
    val tokenA: String? = null,
    val handshakeSelfInitiated: Boolean = false,
    val platformVersionsUrl: String? = null
)

@RestController
@RequestMapping("\${ocn.node.apiPrefix}/admin")
class AdminController(
    private val platformRepo: PlatformRepository,
    private val roleRepo: RoleRepository,
    private val endpointRepo: EndpointRepository,
    private val properties: NodeProperties,
    private val ocnRegistryComponent: OcnRegistryComponent
) {

    fun isAuthorized(authorization: String): Boolean {
        return authorization == "Token ${properties.apikey}" ||
                authorization == "Token ${properties.apikey.toBs64String()}"
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

        val role = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID).firstOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Role not found in this node")

        val platform = platformRepo.findByIdOrNull(role.platformID)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Role found but could not find connection status")

        return ResponseEntity.ok().body(platform.status.toString())
    }

    @PostMapping("/create-platform")
    @Transactional
    fun createPlatform(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody body: CreatePlatformRequest
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(OcpiResponse<Unit>(
                    statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                    statusMessage = "Invalid admin / api key"
                ))
        }

        // check each role does not already exist
        for (role in body.roles) {
            if (roleRepo.existsByCountryCodeAndPartyIDAllIgnoreCase(role.country, role.id)) {
                return ResponseEntity.badRequest()
                    .body(OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Role ${role.country}-${role.id} already exists"
                    ))
            }
        }

        // verify roles exist in blockchain registry
        val myParties = ocnRegistryComponent.findMyPartiesList()
        for (role in body.roles) {
            val partyExists = myParties.any { party ->
                party.countryCode.equals(role.country, ignoreCase = true) &&
                party.partyId.equals(role.id, ignoreCase = true)
            }
            if (!partyExists) {
                return ResponseEntity.badRequest()
                    .body(OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "Role ${role.country}-${role.id} is not registered in OCN blockchain registry"
                    ))
            }
        }

        // validate required fields when handshake is self-initiated
        if (body.handshakeSelfInitiated) {
            if (body.tokenA.isNullOrEmpty()) {
                return ResponseEntity.badRequest()
                    .body(OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "tokenA is required when handshakeSelfInitiated is true"
                    ))
            }
            if (body.platformVersionsUrl.isNullOrEmpty()) {
                return ResponseEntity.badRequest()
                    .body(OcpiResponse<Unit>(
                        statusCode = OcpiStatus.CLIENT_INVALID_PARAMETERS.code,
                        statusMessage = "platformVersionsUrl is required when handshakeSelfInitiated is true"
                    ))
            }
        }

        val platform = if (body.handshakeSelfInitiated) {
            // self-initiated handshake: use provided tokenA, generate selfCredentialsToken, store versionsUrl
            PlatformEntity(
                auth = Auth(
                    tokenA = body.tokenA!!.toBs64String(),
                    selfCredentialsToken = generateUUIDv4Token().toBs64String(),
                    handshakeSelfInitiated = true
                ),
                versionsUrl = body.platformVersionsUrl
            )
        } else {
            // generate and store new platform with authorization token
            val tokenA = generateUUIDv4Token()
            PlatformEntity(
                auth = Auth(
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
            val responseBody = RegistrationInfo(
                platform.auth.tokenA!!.fromBs64String(),
                urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions")
            )
            ResponseEntity.status(HttpStatus.CREATED).body(responseBody)
        }
    }

    @GetMapping("/platform/{countryCode}/{partyID}")
    fun getPlatform(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable countryCode: String,
        @PathVariable partyID: String
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        val role = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID).firstOrNull()
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Role not found in this node")

        val platform = platformRepo.findByIdOrNull(role.platformID)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Role found but could not find connection status")

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

        val role = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID).firstOrNull()

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
        val role = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID).firstOrNull()
            ?: return ResponseEntity.ok().body(null)

        // if role exists platform is suposed to be there too, that is why the error message in this case
        val platform = platformRepo.findByIdOrNull(role.platformID)
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
        val role = roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(countryCode, partyID).firstOrNull()
        if (role != null) {
            // resolve platform before deletions
            val platform = platformRepo.findByIdOrNull(role.platformID)

            // 1) delete endpoints (depend on platform)
            if (platform != null) {
                endpointRepo.deleteByPlatformID(platform.id)
                responseMessage = "Endpoints deleted successfully"
            }

            // 2) delete role (depends on platform)
            roleRepo.delete(role)
            responseMessage = if (responseMessage.isBlank()) "Role deleted successfully" else "$responseMessage | Role deleted successfully"

            // 3) delete platform (after dependents removed)
            if (platform != null) {
                platformRepo.delete(platform)
                responseMessage += " | Platform deleted successfully"
            }
        }

        return ResponseEntity.ok().body(responseMessage)
    }

    @GetMapping("/platforms")
    fun getAllPlatforms(
        @RequestHeader("Authorization") authorization: String
    ): ResponseEntity<Any> {
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }
        val platforms = platformRepo.findAll().map { platform ->
            PlatformWithRolesResponse(
                platform = PlatformDto(
                    id = platform.id,
                    status = platform.status.name,
                    lastUpdated = platform.lastUpdated,
                    versionsUrl = platform.versionsUrl,
                    auth = PlatformAuthDto(
                        tokenA = platform.auth.tokenA,
                        tokenB = platform.auth.tokenB,
                        tokenC = platform.auth.tokenC,
                        selfCredentialsToken = platform.auth.selfCredentialsToken,
                        handshakeSelfInitiated = platform.auth.handshakeSelfInitiated
                    )
                ),
                roles = roleRepo.findAllByPlatformID(platform.id).map { role ->
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
            ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("Failed to refresh registry: ${ex.message}")
        }
    }

}
