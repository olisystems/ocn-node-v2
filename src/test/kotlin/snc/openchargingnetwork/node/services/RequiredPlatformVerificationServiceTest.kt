package snc.openchargingnetwork.node.services

import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.stub
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.config.RequiredPlatformProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.ocpi.BusinessDetails
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiHubUnknownReceiverException
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Role
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

class RequiredPlatformVerificationServiceTest {

    private lateinit var platformRepo: PlatformRepository
    private lateinit var roleRepo: RoleRepository
    private lateinit var registryService: RegistryService
    private lateinit var properties: RequiredPlatformProperties
    private lateinit var service: RequiredPlatformVerificationService

    @BeforeEach
    fun setUp() {
        platformRepo = mock()
        roleRepo = mock()
        registryService = mock()

        properties = RequiredPlatformProperties()
        properties.countryCode = "DE"
        properties.partyId = "BAN"

        val indexerProperties = RegistryIndexerProperties()
        indexerProperties.url = "https://subgraph.example/ocn"

        val nodeProperties = mock<NodeProperties>()
        whenever(nodeProperties.url).thenReturn("http://localhost:9999")

        service =
                RequiredPlatformVerificationService(
                        platformRepo,
                        roleRepo,
                        registryService,
                        properties,
                        indexerProperties,
                        nodeProperties
                )
    }

    @Test
    fun `reports CheckDisabled when the check is switched off`() {
        properties.enabled = false

        assertThat(service.verify()).isInstanceOf(RequiredPlatformStatus.CheckDisabled::class.java)
    }

    @Test
    fun `reports Connected for a CONNECTED platform holding a token C`() {
        givenRole()
        givenPlatform(ConnectionStatus.CONNECTED, tokenC = "token-c")
        whenever(roleRepo.findAllByPlatformID(7L)).thenReturn(listOf(roleEntity()))

        val status = service.verify()

        assertThat(status).isInstanceOf(RequiredPlatformStatus.Connected::class.java)
        val connected = status as RequiredPlatformStatus.Connected
        assertThat(connected.platformId).isEqualTo(7L)
        assertThat(connected.roles).containsExactly("HUB")
    }

    @Test
    fun `reports HandshakeIncomplete for a PLANNED platform`() {
        givenRole()
        givenPlatform(ConnectionStatus.PLANNED, tokenC = null)

        val status = service.verify()

        assertThat(status).isInstanceOf(RequiredPlatformStatus.HandshakeIncomplete::class.java)
        val incomplete = status as RequiredPlatformStatus.HandshakeIncomplete
        assertThat(incomplete.status).isEqualTo(ConnectionStatus.PLANNED)
        assertThat(incomplete.hasTokenC).isFalse()
    }

    @Test
    fun `reports HandshakeIncomplete when CONNECTED but token C is missing`() {
        givenRole()
        givenPlatform(ConnectionStatus.CONNECTED, tokenC = "")

        val status = service.verify()

        assertThat(status).isInstanceOf(RequiredPlatformStatus.HandshakeIncomplete::class.java)
        assertThat((status as RequiredPlatformStatus.HandshakeIncomplete).hasTokenC).isFalse()
    }

    @Test
    fun `reports PlatformMissing with RegisteredToThisNode when the role is absent`() {
        givenNoRole()
        whenever(registryService.isRoleKnown(any(), any())).thenReturn(true)

        val registry = missingRegistryStatus()

        assertThat(registry).isInstanceOf(RegistryStatus.RegisteredToThisNode::class.java)
    }

    @Test
    fun `reports RegisteredToAnotherNode when the party points elsewhere`() {
        givenNoRole()
        whenever(registryService.isRoleKnown(any(), any())).thenReturn(false)
        whenever(registryService.getRemoteNodeUrlOf(any())).thenReturn("https://other-node.example")

        val registry = missingRegistryStatus()

        assertThat(registry).isInstanceOf(RegistryStatus.RegisteredToAnotherNode::class.java)
        assertThat((registry as RegistryStatus.RegisteredToAnotherNode).domain)
                .isEqualTo("https://other-node.example")
    }

    @Test
    fun `reports NotRegistered when the registry has no such party`() {
        givenNoRole()
        registryService.stub {
            on { isRoleKnown(any(), any()) } doAnswer
                    {
                        throw OcpiHubUnknownReceiverException(
                                "Role DE-BAN not registered on OCN Registry"
                        )
                    }
        }

        val registry = missingRegistryStatus()

        assertThat(registry).isInstanceOf(RegistryStatus.NotRegistered::class.java)
    }

    @Test
    fun `reports RegistryUnavailable when the subgraph cannot be reached`() {
        givenNoRole()
        registryService.stub {
            on { isRoleKnown(any(), any()) } doAnswer
                    { throw ResponseStatusException(HttpStatus.METHOD_FAILURE, "subgraph timeout") }
        }

        val registry = missingRegistryStatus()

        assertThat(registry).isInstanceOf(RegistryStatus.RegistryUnavailable::class.java)
    }

    @Test
    fun `reports PlatformMissing when the role points at a platform that no longer exists`() {
        givenRole()
        whenever(platformRepo.findById(7L)).thenReturn(Optional.empty())
        whenever(registryService.isRoleKnown(any(), any())).thenReturn(true)

        assertThat(service.verify()).isInstanceOf(RequiredPlatformStatus.PlatformMissing::class.java)
    }

    @Test
    fun `reports AwaitingHandshake when a Platform exists but has no roles yet`() {
        whenever(
                        roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                                "DE",
                                "BAN"
                        )
                )
                .thenReturn(null)
        val unclaimed =
                PlatformEntity(
                        status = ConnectionStatus.PLANNED,
                        auth = Auth(tokenA = "token-a"),
                        id = 42L
                )
        whenever(platformRepo.findAll()).thenReturn(listOf(unclaimed))
        whenever(roleRepo.findAllByPlatformID(42L)).thenReturn(emptyList())

        val status = service.verify()

        assertThat(status).isInstanceOf(RequiredPlatformStatus.AwaitingHandshake::class.java)
        assertThat((status as RequiredPlatformStatus.AwaitingHandshake).platformIds)
                .containsExactly(42L)
    }

    @Test
    fun `does not consult the registry when an unclaimed Platform is waiting`() {
        whenever(
                        roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                                "DE",
                                "BAN"
                        )
                )
                .thenReturn(null)
        val unclaimed = PlatformEntity(status = ConnectionStatus.PLANNED, auth = Auth(), id = 42L)
        whenever(platformRepo.findAll()).thenReturn(listOf(unclaimed))
        whenever(roleRepo.findAllByPlatformID(42L)).thenReturn(emptyList())

        service.verify()

        verify(registryService, never()).isRoleKnown(any(), any())
    }

    private fun missingRegistryStatus(): RegistryStatus {
        val status = service.verify()
        assertThat(status).isInstanceOf(RequiredPlatformStatus.PlatformMissing::class.java)
        return (status as RequiredPlatformStatus.PlatformMissing).registry
    }

    private fun givenRole() {
        whenever(
                        roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                                "DE",
                                "BAN"
                        )
                )
                .thenReturn(roleEntity())
    }

    /** No role AND no unclaimed platform rows - the genuinely-nothing-there case. */
    private fun givenNoRole() {
        whenever(platformRepo.findAll()).thenReturn(emptyList())
        whenever(
                        roleRepo.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc(
                                "DE",
                                "BAN"
                        )
                )
                .thenReturn(null)
    }

    private fun givenPlatform(status: ConnectionStatus, tokenC: String?) {
        val platform =
                PlatformEntity(
                        status = status,
                        auth = Auth(tokenA = "token-a", tokenB = "token-b", tokenC = tokenC),
                        id = 7L
                )
        whenever(platformRepo.findById(7L)).thenReturn(Optional.of(platform))
    }

    private fun roleEntity() =
            RoleEntity(
                    platformID = 7L,
                    role = Role.HUB,
                    businessDetails = BusinessDetails(name = "Transit BAN DE"),
                    partyID = "BAN",
                    countryCode = "DE",
                    id = 1L
            )
}
