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

package snc.openchargingnetwork.node.controllers

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
import snc.openchargingnetwork.node.models.ocpi.RegistrationInfo
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.generateUUIDv4Token
import snc.openchargingnetwork.node.tools.toBs64String
import snc.openchargingnetwork.node.tools.urlJoin


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

    @PostMapping("/generate-registration-token")
    @Transactional
    fun generateRegistrationToken(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody body: Array<BasicRole>
    ): ResponseEntity<Any> {

        // check admin is authorized
        if (!isAuthorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid admin / api key")
        }

        // check each role does not already exist
        for (role in body) {
            if (roleRepo.existsByCountryCodeAndPartyIDAllIgnoreCase(role.country, role.id)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Role ${role.country}-${role.id} already exists")
            }
        }

        // generate and store new platform with authorization token
        //TODO: schedule deletion after 30 days if status still PLANNED (?)
        val tokenA = generateUUIDv4Token()
        val platform = PlatformEntity(auth = Auth(tokenA = tokenA.toBs64String()))
        platformRepo.save(platform)

        val responseBody = RegistrationInfo(tokenA, urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions"))
        return ResponseEntity.ok().body(responseBody)
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