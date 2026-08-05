package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.repositories.PlatformRepository

@SpringBootTest(classes = [Application::class])
@ActiveProfiles("test")
@Transactional
class CredentialsServiceTest(
        @Autowired private val credentialsService: CredentialsService,
        @Autowired private val platformRepository: PlatformRepository,
        @Autowired private val nodeProperties: NodeProperties
) {

    @Test
    fun `myCredentials advertises the configured node identity as HUB`() {
        val credentials = credentialsService.myCredentials("test-token")

        assertThat(credentials.roles).hasSize(1)
        assertThat(credentials.roles.first().role).isEqualTo(snc.openchargingnetwork.node.models.ocpi.Role.HUB)
        assertThat(credentials.roles.first().countryCode).isEqualTo(nodeProperties.countryCode)
        assertThat(credentials.roles.first().partyID).isEqualTo(nodeProperties.partyId)
    }

    @Test
    fun `postCredentials should reject already connected platform`() {
        platformRepository.save(
                PlatformEntity(
                        status = ConnectionStatus.CONNECTED,
                        auth =
                                Auth(
                                        tokenA = "test-token-a",
                                        tokenB = "test-token-b",
                                        tokenC = "test-token-c"
                                )
                )
        )

        assertThrows<OcpiClientInvalidParametersException> {
            credentialsService.postCredentials(
                    tokenA = "test-token-a",
                    body =
                            snc.openchargingnetwork.node.models.ocpi.Credentials(
                                    token = "new-token-b",
                                    url = "https://new-platform.com/ocpi/versions",
                                    roles = emptyList()
                            )
            )
        }
    }
}
