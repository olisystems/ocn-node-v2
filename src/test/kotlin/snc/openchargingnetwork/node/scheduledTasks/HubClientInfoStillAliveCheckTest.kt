package snc.openchargingnetwork.node.scheduledTasks

import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository

class HubClientInfoStillAliveCheckTest {

    private val properties: NodeProperties = mock()
    private val httpClientComponent: HttpClientComponent = mock()
    private val platformRepository: PlatformRepository = mock()

    private val check =
            HubClientInfoStillAliveCheck(
                    properties = properties,
                    httpClientComponent = httpClientComponent,
                    platformRepo = platformRepository
            )

    @Test
    fun `isClientAvailable should use tokenB when handshake is not self-initiated`() {
        val platform =
                PlatformEntity(
                        status = ConnectionStatus.CONNECTED,
                        lastUpdated = "2020-01-01T00:00:00Z",
                        versionsUrl = "https://platform.com/ocpi/versions",
                        auth =
                                Auth(
                                        tokenB = "token-b",
                                        tokenC = "token-c",
                                        handshakeSelfInitiated = false
                                )
                )

        whenever(properties.stillAliveRate).thenReturn(60000)
        whenever(platformRepository.findByStatusIn(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE)))
                .thenReturn(listOf(platform))
        whenever(httpClientComponent.getVersions("https://platform.com/ocpi/versions", "token-b"))
                .thenReturn(listOf(Version("2.2.1", "https://platform.com/ocpi/2.2.1")))

        check.run()

        verify(httpClientComponent).getVersions("https://platform.com/ocpi/versions", "token-b")
    }

    @Test
    fun `isClientAvailable should use tokenC when handshake is self-initiated`() {
        val platform =
                PlatformEntity(
                        status = ConnectionStatus.CONNECTED,
                        lastUpdated = "2020-01-01T00:00:00Z",
                        versionsUrl = "https://platform.com/ocpi/versions",
                        auth =
                                Auth(
                                        tokenB = "token-b",
                                        tokenC = "token-c",
                                        handshakeSelfInitiated = true
                                )
                )

        whenever(properties.stillAliveRate).thenReturn(60000)
        whenever(platformRepository.findByStatusIn(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE)))
                .thenReturn(listOf(platform))
        whenever(httpClientComponent.getVersions("https://platform.com/ocpi/versions", "token-c"))
                .thenReturn(listOf(Version("2.2.1", "https://platform.com/ocpi/2.2.1")))

        check.run()

        verify(httpClientComponent).getVersions("https://platform.com/ocpi/versions", "token-c")
    }

    @Test
    fun `isClientAvailable should return false when expected token is missing`() {
        val platform =
                PlatformEntity(
                        status = ConnectionStatus.CONNECTED,
                        lastUpdated = "2020-01-01T00:00:00Z",
                        versionsUrl = "https://platform.com/ocpi/versions",
                        auth =
                                Auth(
                                        tokenB = null,
                                        tokenC = null,
                                        handshakeSelfInitiated = false
                                )
                )

        whenever(properties.stillAliveRate).thenReturn(60000)
        whenever(platformRepository.findByStatusIn(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE)))
                .thenReturn(listOf(platform))

        check.run()

        verify(httpClientComponent, never()).getVersions(anyString(), anyString())
    }
}
