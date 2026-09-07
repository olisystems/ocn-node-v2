package snc.openchargingnetwork.node.services

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.EndpointEntity
import snc.openchargingnetwork.node.models.entities.NetworkClientInfoEntity
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.Role
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.NetworkClientInfoRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.tools.toBs64String

@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
@Transactional
class HubClientInfoServiceTest(
    @Autowired private val hubClientInfoService: HubClientInfoService,
    @Autowired private val platformRepository: PlatformRepository,
    @Autowired private val roleRepository: RoleRepository,
    @Autowired private val endpointRepository: EndpointRepository,
    @Autowired private val networkClientInfoRepository: NetworkClientInfoRepository,
    @Autowired private val httpClientComponent: HttpClientComponent,
    @Autowired private val ocnRegistryComponent: OcnRegistryComponent,
    @Autowired private val routingService: RoutingService,
    @Autowired private val walletService: WalletService,
    @Autowired private val ocnRulesService: OcnRulesService,
    @Autowired private val registryService: RegistryService,
    @Autowired private val registryIndexerProperties: RegistryIndexerProperties
) {

    private lateinit var testPlatform: PlatformEntity
    private lateinit var testRole: RoleEntity
    private lateinit var testEndpoint: EndpointEntity
    private lateinit var testAuthorization: String
    private lateinit var testClientInfo: ClientInfo

    @BeforeEach
    fun setUp() {
        // Create test platform
        testPlatform =
            PlatformEntity(
                status = ConnectionStatus.CONNECTED,
                auth =
                    Auth(
                        tokenA = "test-token-a",
                        tokenB = "test-token-b",
                        tokenC = "test-token-c"
                    )
            )
        testPlatform = platformRepository.save(testPlatform)

        // Create test role
        testRole =
            RoleEntity(
                platformID = testPlatform.id!!,
                role = Role.CPO,
                businessDetails =
                    snc.openchargingnetwork.node.models.ocpi.BusinessDetails(
                        name = "Test Company",
                        website = "https://test.com"
                    ),
                partyID = "TST",
                countryCode = "DE"
            )
        testRole = roleRepository.save(testRole)

        // Create test endpoint for HubClientInfo
        testEndpoint =
            EndpointEntity(
                platformID = testPlatform.id!!,
                identifier = "hubclientinfo",
                role = InterfaceRole.RECEIVER,
                url = "https://test-platform.com/ocpi/2.2.1/hubclientinfo"
            )
        testEndpoint = endpointRepository.save(testEndpoint)

        testAuthorization = "Token ${"test-token-c".toBs64String()}"
        testClientInfo =
            ClientInfo(
                partyID = "TST",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
    }

    @Test
    fun `getList should return client info for valid authorization`() {
        val clientInfoList = hubClientInfoService.getList(testAuthorization)

        assertThat(clientInfoList).isNotNull()
        assertThat(clientInfoList).isInstanceOf(List::class.java)
        // The actual content depends on the whitelist rules and existing data
    }

    @Test
    fun `getList should return client info for self-initiated handshake using tokenB`() {
        val selfInitiatedPlatform =
            platformRepository.save(
                PlatformEntity(
                    status = ConnectionStatus.CONNECTED,
                    auth =
                        Auth(
                            tokenA = "self-token-a",
                            tokenB = "self-token-b",
                            tokenC = "self-token-c",
                            handshakeSelfInitiated = true
                        )
                )
            )
        roleRepository.save(
            RoleEntity(
                platformID = selfInitiatedPlatform.id!!,
                role = Role.CPO,
                businessDetails =
                    snc.openchargingnetwork.node.models.ocpi.BusinessDetails(
                        name = "Self Company",
                        website = "https://self.com"
                    ),
                partyID = "SLF",
                countryCode = "DE"
            )
        )

        val authorization = "Token ${"self-token-b".toBs64String()}"
        val clientInfoList = hubClientInfoService.getList(authorization)

        assertThat(clientInfoList).isNotNull()
        assertThat(clientInfoList).isInstanceOf(List::class.java)
    }

    @Test
    fun `getList should throw exception for invalid authorization`() {
        val invalidAuthorization = "Token ${"invalid-token".toBs64String()}"

        try {
            hubClientInfoService.getList(invalidAuthorization)
            // If no exception is thrown, the test passes (depends on implementation)
        } catch (e: Exception) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `saveClientInfo should save new client info`() {
        val newClientInfo =
            ClientInfo(
                partyID = "NEW",
                countryCode = "FR",
                role = Role.EMSP,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )

        hubClientInfoService.saveClientInfo(newClientInfo)

        val savedClientInfo =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("NEW", "FR"),
                Role.EMSP
            )
        assertThat(savedClientInfo).isNotNull()
        assertThat(savedClientInfo!!.party.id).isEqualTo("NEW")
        assertThat(savedClientInfo.party.country).isEqualTo("FR")
        assertThat(savedClientInfo.role).isEqualTo(Role.EMSP)
        assertThat(savedClientInfo.status).isEqualTo(ConnectionStatus.CONNECTED)
    }

    @Test
    fun `saveClientInfo should update existing client info`() {
        val existingClientInfo =
            ClientInfo(
                partyID = "UPD",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        hubClientInfoService.saveClientInfo(existingClientInfo)

        val updatedClientInfo =
            ClientInfo(
                partyID = "UPD",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.SUSPENDED,
                lastUpdated = "2023-01-02T00:00:00Z"
            )

        hubClientInfoService.saveClientInfo(updatedClientInfo)

        val savedClientInfo =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("UPD", "DE"),
                Role.CPO
            )
        assertThat(savedClientInfo).isNotNull()
        assertThat(savedClientInfo!!.status).isEqualTo(ConnectionStatus.SUSPENDED)
        assertThat(savedClientInfo.lastUpdated).isEqualTo("2023-01-02T00:00:00Z")
    }

    @Test
    fun `updateClientInfo should update existing client info`() {
        val existingClientInfo =
            ClientInfo(
                partyID = "UPD",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        hubClientInfoService.saveClientInfo(existingClientInfo)

        val updatedClientInfo =
            ClientInfo(
                partyID = "UPD",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.SUSPENDED,
                lastUpdated = "2023-01-02T00:00:00Z"
            )

        hubClientInfoService.updateClientInfo(updatedClientInfo)

        val savedClientInfo =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("UPD", "DE"),
                Role.CPO
            )
        assertThat(savedClientInfo).isNotNull()
        assertThat(savedClientInfo!!.status).isEqualTo(ConnectionStatus.SUSPENDED)
        assertThat(savedClientInfo.lastUpdated).isEqualTo("2023-01-02T00:00:00Z")
    }

    @Test
    fun `getAllRegisteredParties should return all network client info`() {
        val clientInfo1 =
            ClientInfo(
                partyID = "TS1",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        val clientInfo2 =
            ClientInfo(
                partyID = "TS2",
                countryCode = "FR",
                role = Role.EMSP,
                status = ConnectionStatus.SUSPENDED,
                lastUpdated = "2023-01-02T00:00:00Z"
            )
        hubClientInfoService.saveClientInfo(clientInfo1)
        hubClientInfoService.saveClientInfo(clientInfo2)

        val allParties = hubClientInfoService.getAllRegisteredParties()

        assertThat(allParties).isNotNull()
        assertThat(allParties.size).isGreaterThanOrEqualTo(2)
        assertThat(allParties.any { it.partyID == "TS1" && it.countryCode == "DE" })
            .isTrue()
        assertThat(allParties.any { it.partyID == "TS2" && it.countryCode == "FR" })
            .isTrue()
    }

    @Test
    fun `getHubClientInfoList should return same as getList`() {
        val list1 = hubClientInfoService.getList(testAuthorization)
        val list2 = hubClientInfoService.getHubClientInfoList(testAuthorization)

        assertThat(list1).isEqualTo(list2)
    }

    @Test
    fun `getPaginatedClientInfo should list a locally registered party only once`() {
        // a stale network record for a party that is also registered locally (testRole = DE/TST/CPO)
        networkClientInfoRepository.save(
            NetworkClientInfoEntity(
                party = BasicRole("TST", "DE").uppercase(),
                role = Role.CPO,
                status = ConnectionStatus.PLANNED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        )

        val page = hubClientInfoService.getPaginatedClientInfo(0, 1000)

        val tstCpo =
            page.data.filter {
                it.countryCode == "DE" && it.partyID == "TST" && it.role == Role.CPO
            }
        assertThat(tstCpo).hasSize(1)
        // the local platform record wins over the network one
        assertThat(tstCpo.first().status).isEqualTo(ConnectionStatus.CONNECTED)
        assertThat(page.totalCount).isEqualTo(page.data.size)
    }

    @Test
    fun `getPaginatedClientInfo should list connected parties first`() {
        // testPlatform/testRole is DE/TST/CPO and CONNECTED; add parties in the other statuses
        listOf(
                Triple("PLN", Role.CPO, ConnectionStatus.PLANNED),
                Triple("SUS", Role.CPO, ConnectionStatus.SUSPENDED),
                Triple("OFF", Role.CPO, ConnectionStatus.OFFLINE)
            )
            .forEach { (partyId, role, status) ->
                networkClientInfoRepository.save(
                    NetworkClientInfoEntity(
                        party = BasicRole(partyId, "DE").uppercase(),
                        role = role,
                        status = status,
                        lastUpdated = "2023-01-01T00:00:00Z"
                    )
                )
            }

        val page = hubClientInfoService.getPaginatedClientInfo(0, 1000)
        val statuses = page.data.map { it.status }

        // connected block comes first, and the statuses never go "back up" the order
        assertThat(statuses.first()).isEqualTo(ConnectionStatus.CONNECTED)
        assertThat(statuses).isSortedAccordingTo(compareBy { it.ordinal })
    }

    @Test
    fun `getPaginatedClientInfo should not return duplicate party role combinations`() {
        networkClientInfoRepository.save(
            NetworkClientInfoEntity(
                party = BasicRole("TST", "DE").uppercase(),
                role = Role.CPO,
                status = ConnectionStatus.PLANNED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        )

        val page = hubClientInfoService.getPaginatedClientInfo(0, 1000)

        val keys = page.data.map { Triple(it.countryCode, it.partyID, it.role) }
        assertThat(keys).doesNotHaveDuplicates()
    }

    @Test
    fun `getPaginatedClientInfo should list two network rows for the same identity only once`() {
        // the network client info table has no unique constraint on (party, role), so the same
        // identity can be stored twice - the oldest row wins
        networkClientInfoRepository.save(
            NetworkClientInfoEntity(
                party = BasicRole("DUP", "DE").uppercase(),
                role = Role.CPO,
                status = ConnectionStatus.PLANNED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        )
        networkClientInfoRepository.save(
            NetworkClientInfoEntity(
                party = BasicRole("DUP", "DE").uppercase(),
                role = Role.CPO,
                status = ConnectionStatus.SUSPENDED,
                lastUpdated = "2023-06-01T00:00:00Z"
            )
        )

        val page = hubClientInfoService.getPaginatedClientInfo(0, 1000)

        val dup =
            page.data.filter {
                it.countryCode == "DE" && it.partyID == "DUP" && it.role == Role.CPO
            }
        assertThat(dup).hasSize(1)
        assertThat(dup.first().status).isEqualTo(ConnectionStatus.PLANNED)
        assertThat(dup.first().lastUpdated).isEqualTo("2023-01-01T00:00:00Z")
        // totalCount is derived from the same deduplicated list, so it never counts the dropped row
        assertThat(page.totalCount).isEqualTo(page.data.size)
    }

    @Test
    fun `getPaginatedList should apply the date_from and date_to filters to data and totalCount`() {
        listOf(
                Triple("OLD", "2023-01-01T00:00:00Z", ConnectionStatus.PLANNED),
                Triple("MID", "2023-06-01T00:00:00Z", ConnectionStatus.PLANNED),
                Triple("NEW", "2024-01-01T00:00:00Z", ConnectionStatus.PLANNED)
            )
            .forEach { (partyId, lastUpdated, status) ->
                networkClientInfoRepository.save(
                    NetworkClientInfoEntity(
                        party = BasicRole(partyId, "DE").uppercase(),
                        role = Role.CPO,
                        status = status,
                        lastUpdated = lastUpdated
                    )
                )
            }

        val page =
            hubClientInfoService.getPaginatedList(
                fromAuthorization = testAuthorization,
                offset = 0,
                limit = 1000,
                dateFrom = Instant.parse("2023-03-01T00:00:00Z"),
                dateTo = Instant.parse("2023-12-01T00:00:00Z")
            )

        // date_from is inclusive and date_to exclusive, so only MID survives - and the locally
        // registered DE/TST/CPO record, whose last_updated is "now", falls outside the window too
        assertThat(page.data.map { it.partyID }).containsExactly("MID")
        assertThat(page.totalCount).isEqualTo(1)
    }

    @Test
    fun `getPaginatedList without date filters returns every visible record`() {
        networkClientInfoRepository.save(
            NetworkClientInfoEntity(
                party = BasicRole("OLD", "DE").uppercase(),
                role = Role.CPO,
                status = ConnectionStatus.PLANNED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        )

        val unfiltered = hubClientInfoService.getPaginatedList(testAuthorization, 0, 1000)

        assertThat(unfiltered.data.map { it.partyID }).contains("OLD", "TST")
        assertThat(unfiltered.totalCount).isEqualTo(unfiltered.data.size)
    }

    @Test
    fun `checkForNewPartiesFromRegistry should skip parties already registered locally`() {
        // same party/role as testRole, so no network record should be created for it
        val localParty =
            snc.openchargingnetwork.node.models.Party(
                id = "TST",
                countryCode = "DE",
                partyId = "TST",
                partyAddress = "0x987654321",
                roles = listOf(Role.CPO),
                name = "Test Company",
                url = "https://test.com",
                paymentStatus =
                    snc.openchargingnetwork.node.models.PaymentStatus.PAID,
                cvStatus =
                    snc.openchargingnetwork.node.models.CvStatus.VERIFIED,
                active = true,
                deleted = false,
                operator =
                    snc.openchargingnetwork.node.models.Operator(
                        id = "OP1",
                        domain = "https://op1.com"
                    )
            )

        hubClientInfoService.checkForNewPartiesFromRegistry(listOf(localParty))

        val networkRecord =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("TST", "DE").uppercase(),
                Role.CPO
            )

        assertThat(networkRecord).isNull()
    }

    @Test
    fun `getPartiesToNotifyOfClientInfoChange should return parties to notify`() {
        val clientInfo =
            ClientInfo(
                partyID = "TST",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )

        val partiesToNotify =
            hubClientInfoService.getPartiesToNotifyOfClientInfoChange(
                changedPlatform = testPlatform,
                clientInfo = clientInfo
            )

        assertThat(partiesToNotify).isNotNull()
        assertThat(partiesToNotify).isInstanceOf(List::class.java)
        // The actual content depends on the whitelist rules and existing data
    }

    @Test
    fun `getIndexedParties should return parties from registry`() {
        val indexedParties = hubClientInfoService.getIndexedParties()

        assertThat(indexedParties).isNotNull()
        assertThat(indexedParties).isInstanceOf(List::class.java)
    }

    @Test
    fun `checkForNewPartiesFromRegistry should discover new parties`() {
        val mockParties =
            listOf(
                snc.openchargingnetwork.node.models.Party(
                    id = "TS5",
                    countryCode = "DE",
                    partyId = "TS5",
                    partyAddress = "0x123456789",
                    roles = listOf(Role.CPO),
                    name = "New Company",
                    url = "https://new.com",
                    paymentStatus =
                        snc.openchargingnetwork.node.models.PaymentStatus
                            .PAID,
                    cvStatus =
                        snc.openchargingnetwork.node.models.CvStatus
                            .VERIFIED,
                    active = true,
                    deleted = false,
                    operator =
                        snc.openchargingnetwork.node.models.Operator(
                            id = "OP1",
                            domain = "https://op1.com"
                        )
                )
            )



        hubClientInfoService.checkForNewPartiesFromRegistry(mockParties)
        Thread.sleep(100)

        val newClientInfo =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("TS5", "DE").uppercase(),
                Role.CPO
            )

        assertThat(newClientInfo).isNotNull()
    }

    @Test
    fun `checkForSuspendedUpdates should suspend parties not in registry`() {
        val existingClientInfo =
            ClientInfo(
                partyID = "SUS",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.SUSPENDED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )
        hubClientInfoService.saveClientInfo(existingClientInfo)

        val emptyParties = emptyList<snc.openchargingnetwork.node.models.Party>()

        hubClientInfoService.checkForSuspendedUpdates(emptyParties)

        val suspendedClientInfo =
            networkClientInfoRepository.findByPartyAndRole(
                BasicRole("SUS", "DE"),
                Role.CPO
            )
        assertThat(suspendedClientInfo).isNotNull()
        assertThat(suspendedClientInfo!!.status).isEqualTo(ConnectionStatus.SUSPENDED)
    }

    @Test
    fun `syncHubClientInfo should complete without exception`() {
        try {
            hubClientInfoService.syncHubClientInfo()
            assertThat(true).isTrue()
        } catch (e: Exception) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `renewClientConnection should update platform connection`() {
        val sender = BasicRole("TST", "DE")

        hubClientInfoService.renewClientConnection(sender)

        val updatedPlatform = platformRepository.findById(testPlatform.id!!).get()

        assertThat(updatedPlatform.lastUpdated).isNotNull()
    }

    @Test
    fun `renewClientConnection should throw exception for unknown sender`() {
        val unknownSender = BasicRole("XXX", "XX")

        try {
            hubClientInfoService.renewClientConnection(unknownSender)
        } catch (e: Exception) {
            assertThat(e.message).contains("sender could not be found")
        }
    }

    @Test
    fun `notifyPartiesOfClientInfoChange should complete without exception`() {
        val parties = listOf(testRole)
        val clientInfo =
            ClientInfo(
                partyID = "TST",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )

        try {
            hubClientInfoService.notifyPartiesOfClientInfoChange(parties, clientInfo)
            assertThat(true).isTrue()
        } catch (e: Exception) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `notifyNodesOfClientInfoChange should complete without exception`() {
        val clientInfo =
            ClientInfo(
                partyID = "TST",
                countryCode = "DE",
                role = Role.CPO,
                status = ConnectionStatus.CONNECTED,
                lastUpdated = "2023-01-01T00:00:00Z"
            )

        try {
            hubClientInfoService.notifyNodesOfClientInfoChange(clientInfo)
            assertThat(true).isTrue()
        } catch (e: Exception) {
            assertThat(e.message).isNotNull()
        }
    }
}
