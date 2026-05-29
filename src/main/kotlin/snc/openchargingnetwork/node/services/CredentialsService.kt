package snc.openchargingnetwork.node.services

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.EndpointEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.exceptions.OcpiServerNoMatchingEndpointsException
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.repositories.*
import snc.openchargingnetwork.node.tools.*

@Service
class CredentialsService(
        private val platformRepo: PlatformRepository,
        private val roleRepo: RoleRepository,
        private val endpointRepo: EndpointRepository,
        private val networkClientInfoRepository: NetworkClientInfoRepository,
        private val ocnRulesListRepo: OcnRulesListRepository,
        private val properties: NodeProperties,
        private val registryService: RegistryService,
        private val httpClientComponent: HttpClientComponent
) {

    companion object {
        private val logger = LoggerFactory.getLogger(CredentialsService::class.java)
    }

    fun myCredentials(token: String): Credentials {
        return Credentials(
                token = token,
                url = urlJoin(properties.url, properties.apiPrefix, "/ocpi/versions"),
                roles =
                        listOf(
                                CredentialsRole(
                                        role = Role.HUB,
                                        businessDetails =
                                                BusinessDetails(
                                                        name = "OCN Node Managed by DE BAN"
                                                ),
                                        partyID = "BAN",
                                        countryCode = "DE"
                                )
                        )
        )
    }

    fun getCredentials(token: String): OcpiResponse<Credentials> {
        val platformByTokenB = platformRepo.findByAuth_TokenB(token)
        val platformByTokenC = platformRepo.findByAuth_TokenC(token)

        val platform =
                if (platformByTokenB?.auth?.handshakeSelfInitiated == true) {
                    platformByTokenB
                } else if (platformByTokenC?.auth?.handshakeSelfInitiated == false) {
                    platformByTokenC
                } else {
                    throw OcpiClientInvalidParametersException(
                            "Invalid token — tried TokenB (handshakeSelfInitiated=true) and TokenC (handshakeSelfInitiated=false)"
                    )
                }

        val responseToken =
                if (platform.auth.handshakeSelfInitiated == true) {
                    platform.auth.tokenB!!.fromBs64String()
                } else {
                    platform.auth.tokenC!!.fromBs64String()
                }

        return OcpiResponse(
                statusCode = OcpiStatus.SUCCESS.code,
                data = myCredentials(responseToken)
        )
    }

    @Transactional
    fun postCredentials(tokenA: String, body: Credentials): OcpiResponse<Credentials> {
        logger.debug(
                "Received POST credentials request for url={} with rolesCount={}",
                body.url,
                body.roles.size
        )

        // TODO: detect changes to public URL to automatically update credentials on connected
        // platforms

        // check platform previously registered by admin
        val platform =
                platformRepo.findByAuth_TokenA(tokenA)
                        ?: throw OcpiClientInvalidParametersException("Invalid CREDENTIALS_TOKEN_A")

        // GET versions information endpoint with TOKEN_B (both provided in request body)
        val versionsInfo = httpClientComponent.getVersions(body.url, body.token.toBs64String())

        // try to match version 2.2
        val correctVersion =
                versionsInfo.firstOrNull { it.version == "2.2.1" || it.version == "2.2" }
                        ?: throw OcpiServerNoMatchingEndpointsException(
                                "Expected version 2.2 or 2.2.1 from $versionsInfo"
                        )

        // GET 2.2 version details
        val versionDetail =
                httpClientComponent.getVersionDetail(correctVersion.url, body.token.toBs64String())

        // ensure each role does not already exist; delete if planned
        for (role in body.roles) {
            val basicRole = BasicRole(role.partyID, role.countryCode)
            if (!registryService.isRoleKnown(basicRole)) {
                throw OcpiClientInvalidParametersException(
                        "Role with party_id=${basicRole.id} and country_code=${basicRole.country} not listed in OCN Registry with my node info"
                )
            }
            if (roleRepo.existsByCountryCodeAndPartyIDAllIgnoreCase(basicRole.country, basicRole.id)
            ) {
                val existingRoles =
                        roleRepo.findAllByCountryCodeAndPartyIDAllIgnoreCase(
                                basicRole.country,
                                basicRole.id
                        )
                val platformIDs = existingRoles.map { it.platformID }.distinct()
                for (pid in platformIDs) {
                    endpointRepo.deleteByPlatformID(pid)
                    roleRepo.deleteByPlatformID(pid)
                }
            }
            if (networkClientInfoRepository.existsByPartyAndRole(basicRole.uppercase(), role.role)
            ) {
                networkClientInfoRepository.deleteByPartyAndRole(basicRole.uppercase(), role.role)
            }
        }

        // generate TOKEN_C
        val tokenC = generateUUIDv4Token()

        // set platform connection details
        platform.auth =
                Auth(
                        tokenA = platform.auth.tokenA,
                        selfCredentialsToken = platform.auth.selfCredentialsToken,
                        handshakeSelfInitiated = platform.auth.handshakeSelfInitiated,
                        tokenB = body.token.toBs64String(),
                        tokenC = tokenC.toBs64String()
                )
        platform.versionsUrl = body.url
        platform.status = ConnectionStatus.CONNECTED
        platform.lastUpdated = getTimestamp()
        platform.rules.signatures = properties.signatures

        // set platform's roles' credentials with deduplication
        val roles = mutableListOf<RoleEntity>()

        for (role in body.roles) {
            // Delete any existing role with same (country_code, party_id, role)
            roleRepo.deleteByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
                    role.countryCode,
                    role.partyID,
                    role.role
            )

            roles.add(
                    RoleEntity(
                            platformID = platform.id!!,
                            role = role.role,
                            businessDetails = role.businessDetails,
                            partyID = role.partyID,
                            countryCode = role.countryCode
                    )
            )
        }

        platform.register(roles)
        platformRepo.save(platform)
        roleRepo.saveAll(roles)

        // set platform's endpoints
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

        // Send all connected parties to the newly connected party if it supports HubClientInfo
        if (roles.size > 0) {
            platform.sendAllPartiesToNewlyConnectedParty(
                    roles[0].countryCode,
                    roles[0].partyID,
            )
        }

        // return OCN's platform connection information and role credentials
        return OcpiResponse(statusCode = OcpiStatus.SUCCESS.code, data = myCredentials(tokenC))
    }

    @Transactional
    fun putCredentials(tokenC: String, body: Credentials): OcpiResponse<Credentials> {
        logger.debug(
                "Received PUT credentials request for url={} with {} roles",
                body.url,
                body.roles.size
        )

        // find platform (required to have already been fully registered)
        val platform =
                platformRepo.findByAuth_TokenC(tokenC)
                        ?: throw OcpiClientInvalidParametersException("Invalid CREDENTIALS_TOKEN_C")

        // GET versions information endpoint with TOKEN_B (both provided in request body)
        val versionsInfo: List<Version> =
                httpClientComponent.getVersions(body.url, body.token.toBs64String())

        // try to match version 2.2 or 2.2.1
        val correctVersion =
                versionsInfo.firstOrNull { it.version == "2.2.1" || it.version == "2.2" }
                        ?: throw OcpiServerNoMatchingEndpointsException(
                                "Expected version 2.2 or 2.2.1 from $versionsInfo"
                        )

        // GET version details
        val versionDetail =
                httpClientComponent.getVersionDetail(correctVersion.url, body.token.toBs64String())

        // generate TOKEN_C
        val newTokenC = generateUUIDv4Token()

        // set platform connection information
        platform.auth =
                Auth(
                        tokenA = platform.auth.tokenA,
                        selfCredentialsToken = platform.auth.selfCredentialsToken,
                        handshakeSelfInitiated = platform.auth.handshakeSelfInitiated,
                        tokenB = body.token.toBs64String(),
                        tokenC = newTokenC.toBs64String()
                )
        platform.versionsUrl = body.url
        platform.status = ConnectionStatus.CONNECTED
        platform.lastUpdated = getTimestamp()

        endpointRepo.deleteByPlatformID(platform.id)
        roleRepo.deleteByPlatformID(platform.id)

        // set platform's roles' credentials with deduplication
        val roles = mutableListOf<RoleEntity>()

        for (role in body.roles) {
            // Delete any existing role with same (country_code, party_id, role)
            roleRepo.deleteByCountryCodeAndPartyIDAndRoleAllIgnoreCase(
                    role.countryCode,
                    role.partyID,
                    role.role
            )

            roles.add(
                    RoleEntity(
                            platformID = platform.id!!,
                            role = role.role,
                            businessDetails = role.businessDetails,
                            partyID = role.partyID,
                            countryCode = role.countryCode
                    )
            )
        }

        platform.register(roles)
        platformRepo.save(platform)
        roleRepo.saveAll(roles)

        // set platform's endpoints
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

        // Send all connected parties to the newly connected party if it supports HubClientInfo
        if (roles.size > 0) {
            platform.sendAllPartiesToNewlyConnectedParty(
                    roles[0].countryCode,
                    roles[0].partyID,
            )
        }

        // return OCN Node's platform connection information and role credentials (same for all
        // nodes)
        return OcpiResponse(statusCode = OcpiStatus.SUCCESS.code, data = myCredentials(newTokenC))
    }

    @Transactional
    fun deleteCredentials(tokenC: String): OcpiResponse<Nothing?> {
        val platform =
                platformRepo.findByAuth_TokenC(tokenC)
                        ?: throw OcpiClientInvalidParametersException("Invalid CREDENTIALS_TOKEN_C")

        val roles = roleRepo.findAllByPlatformID(platform.id)
        platform.unregister(roles)
        platformRepo.save(platform)

        roleRepo.deleteByPlatformID(platform.id)
        endpointRepo.deleteByPlatformID(platform.id)
        ocnRulesListRepo.deleteByPlatformID(platform.id)
        platformRepo.deleteById(platform.id!!)

        return OcpiResponse(statusCode = 1000, data = null)
    }
}
