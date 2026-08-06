package snc.openchargingnetwork.node.services

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.Application
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
        @Autowired private val platformRepository: PlatformRepository
) {

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
