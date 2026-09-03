package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import snc.openchargingnetwork.node.tools.getTimestamp

class StillAliveServiceTest {

    private val properties: NodeProperties = mock()
    private val httpClientComponent: HttpClientComponent = mock()
    private val platformRepository: PlatformRepository = mock()
    private val roleRepository: RoleRepository = mock()

    private val service =
            StillAliveService(
                    properties = properties,
                    httpClientComponent = httpClientComponent,
                    platformRepo = platformRepository,
                    roleRepo = roleRepository
            )

    // this module runs JUnit with a per_class test instance lifecycle, so the mocks above are
    // shared by every test in this class and have to be cleared between them
    @BeforeEach
    fun resetMocks() {
        reset(properties, httpClientComponent, platformRepository, roleRepository)
    }

    private fun platform(
            handshakeSelfInitiated: Boolean = false,
            tokenB: String? = "token-b",
            tokenC: String? = "token-c",
            lastUpdated: String = "2020-01-01T00:00:00Z"
    ) =
            PlatformEntity(
                    status = ConnectionStatus.CONNECTED,
                    lastUpdated = lastUpdated,
                    versionsUrl = "https://platform.com/ocpi/versions",
                    auth =
                            Auth(
                                    tokenB = tokenB,
                                    tokenC = tokenC,
                                    handshakeSelfInitiated = handshakeSelfInitiated
                            )
            )

    private fun givenPlatforms(vararg platforms: PlatformEntity) {
        whenever(
                        platformRepository.findByStatusIn(
                                listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE)
                        )
                )
                .thenReturn(platforms.toList())
    }

    @Test
    fun `isClientAvailable should use tokenB when handshake is not self-initiated`() {
        givenPlatforms(platform(handshakeSelfInitiated = false))
        whenever(properties.stillAliveRate).thenReturn(60000)
        whenever(httpClientComponent.getVersions("https://platform.com/ocpi/versions", "token-b"))
                .thenReturn(listOf(Version("2.2.1", "https://platform.com/ocpi/2.2.1")))

        service.checkStalePlatforms()

        verify(httpClientComponent).getVersions("https://platform.com/ocpi/versions", "token-b")
    }

    @Test
    fun `isClientAvailable should use tokenC when handshake is self-initiated`() {
        givenPlatforms(platform(handshakeSelfInitiated = true))
        whenever(properties.stillAliveRate).thenReturn(60000)
        whenever(httpClientComponent.getVersions("https://platform.com/ocpi/versions", "token-c"))
                .thenReturn(listOf(Version("2.2.1", "https://platform.com/ocpi/2.2.1")))

        service.checkStalePlatforms()

        verify(httpClientComponent).getVersions("https://platform.com/ocpi/versions", "token-c")
    }

    @Test
    fun `isClientAvailable should return false when expected token is missing`() {
        val platform = platform(tokenB = null, tokenC = null)
        givenPlatforms(platform)
        whenever(properties.stillAliveRate).thenReturn(60000)

        val summary = service.checkStalePlatforms()

        verify(httpClientComponent, never()).getVersions(anyString(), anyString())
        assertThat(platform.status).isEqualTo(ConnectionStatus.OFFLINE)
        assertThat(summary.offline).isEqualTo(1)
    }

    @Test
    fun `checkStalePlatforms should skip a platform heard from within the still alive rate`() {
        givenPlatforms(platform(lastUpdated = getTimestamp()))
        whenever(properties.stillAliveRate).thenReturn(900000)

        val summary = service.checkStalePlatforms()

        verify(httpClientComponent, never()).getVersions(anyString(), anyString())
        assertThat(summary.skipped).isEqualTo(1)
        assertThat(summary.checked).isEqualTo(0)
    }

    @Test
    fun `checkAllPlatforms should check a recently seen platform regardless of the still alive rate`() {
        val platform = platform(lastUpdated = getTimestamp())
        givenPlatforms(platform)
        whenever(httpClientComponent.getVersions("https://platform.com/ocpi/versions", "token-b"))
                .thenReturn(listOf(Version("2.2.1", "https://platform.com/ocpi/2.2.1")))

        val summary = service.checkAllPlatforms()

        verify(httpClientComponent).getVersions("https://platform.com/ocpi/versions", "token-b")
        assertThat(summary.checked).isEqualTo(1)
        assertThat(summary.connected).isEqualTo(1)
        assertThat(summary.skipped).isEqualTo(0)
        verify(properties, never()).stillAliveRate
    }

    @Test
    fun `checkParty should fail when the party is not registered on this node`() {
        whenever(roleRepository.findFirstByCountryCodeAndPartyIDAllIgnoreCaseOrderByIdAsc("DE", "CST"))
                .thenReturn(null)

        assertThatThrownBy { service.checkParty(BasicRole(id = "CST", country = "DE")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("not registered on this node")

        verify(httpClientComponent, never()).getVersions(anyString(), anyString())
    }
}
