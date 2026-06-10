package snc.openchargingnetwork.node.scheduledTasks

import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.tools.getTimestamp
import java.time.Instant

class HubClientInfoStillAliveCheckTest {
    @Test
    fun `run should use tokenC when tokenB is missing`() {
        val properties = NodeProperties().apply { stillAliveRate = 1_000L }
        val httpClientComponent = mock<HttpClientComponent>()
        val platformRepository = mock<PlatformRepository>()
        val task = HubClientInfoStillAliveCheck(properties, httpClientComponent, platformRepository)

        val client =
            PlatformEntity(
                status = ConnectionStatus.CONNECTED,
                lastUpdated = getTimestamp(Instant.EPOCH),
                versionsUrl = "https://party.example/ocpi/versions",
                auth = Auth(tokenA = null, tokenB = null, tokenC = "token-c")
            )

        whenever(
                platformRepository.findByStatusIn(
                    eq(listOf(ConnectionStatus.CONNECTED, ConnectionStatus.OFFLINE))
                )
            )
            .thenReturn(listOf(client))
        whenever(httpClientComponent.getVersions(client.versionsUrl!!, client.auth.tokenC!!))
            .thenReturn(listOf(Version(version = "2.2.1", url = "https://party.example/ocpi/2.2.1")))

        task.run()

        verify(httpClientComponent).getVersions(client.versionsUrl!!, client.auth.tokenC!!)
        verify(platformRepository).save(client)
    }
}
