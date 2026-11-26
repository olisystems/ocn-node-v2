package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionOperations
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiServerUnusableApiException
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Version
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

/** The still alive check against a real database, with a change landing while the ping runs. */
@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
class StillAliveConcurrentChangeTest(
        @Autowired private val platformRepo: PlatformRepository,
        @Autowired private val roleRepo: RoleRepository,
        @Autowired private val transactions: TransactionOperations
) {

    private val http: HttpClientComponent = mock()
    private val service = StillAliveService(NodeProperties(), http, platformRepo, roleRepo, transactions)
    private val saved = mutableListOf<Long>()

    @AfterEach
    fun cleanUp() {
        platformRepo.deleteAllById(saved)
        saved.clear()
    }

    private fun storedPlatform(): Long {
        val platform =
                platformRepo.save(
                        PlatformEntity(
                                status = ConnectionStatus.CONNECTED,
                                lastUpdated = "2020-01-01T00:00:00Z",
                                versionsUrl = "https://platform.test/ocpi/versions",
                                auth = Auth(tokenB = "old-token-b", tokenC = "old-token-c")
                        )
                )
        return platform.id!!.also { saved.add(it) }
    }

    private fun duringThePing(change: (PlatformEntity) -> Unit, pingFails: Boolean) {
        doAnswer {
                    for (id in saved) {
                        val platform = platformRepo.findById(id).get()
                        change(platform)
                        platformRepo.save(platform)
                    }
                    if (pingFails) throw OcpiServerUnusableApiException("down")
                    listOf(Version("2.2.1", "https://platform.test/ocpi/2.2.1"))
                }
                .whenever(http)
                .getVersions(any(), any())
    }

    @Test
    fun `credentials replaced during the ping are not written back`() {
        val id = storedPlatform()
        duringThePing({ it.auth.tokenB = "new-token-b"; it.auth.tokenC = "new-token-c" }, pingFails = true)

        service.checkAllPlatforms()

        val platform = platformRepo.findById(id).get()
        assertThat(platform.auth.tokenB).isEqualTo("new-token-b")
        assertThat(platform.auth.tokenC).isEqualTo("new-token-c")
        // the ping used the replaced tokens, so it says nothing about the platform as it is now
        assertThat(platform.status).isEqualTo(ConnectionStatus.CONNECTED)
    }

    @Test
    fun `a platform suspended during the ping stays suspended`() {
        val id = storedPlatform()
        duringThePing({ it.status = ConnectionStatus.SUSPENDED }, pingFails = false)

        service.checkAllPlatforms()

        assertThat(platformRepo.findById(id).get().status).isEqualTo(ConnectionStatus.SUSPENDED)
    }

    @Test
    fun `a change the ping does not depend on is kept and the status is still updated`() {
        val id = storedPlatform()
        duringThePing({ it.rules.whitelist = true }, pingFails = true)

        service.checkAllPlatforms()

        val platform = platformRepo.findById(id).get()
        assertThat(platform.rules.whitelist).isTrue()
        assertThat(platform.status).isEqualTo(ConnectionStatus.OFFLINE)
    }
}
