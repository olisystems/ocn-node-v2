package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.TestHttpClientComponent
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus
import snc.openchargingnetwork.node.models.ocpi.Role
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

@SpringBootTest(classes = [TestHttpClientComponent::class])
@ActiveProfiles("test")
@Transactional
class VersionsServiceTest(
        @Autowired private val versionsService: VersionsService,
        @Autowired private val roleRepository: RoleRepository,
        @Autowired private val platformRepository: PlatformRepository,
        @Autowired private val httpClientComponent: HttpClientComponent
) {

        private lateinit var testPlatform: PlatformEntity
        private lateinit var testRole: RoleEntity

        @BeforeEach
        fun setUp() {
                // Create a test platform
                testPlatform =
                        PlatformEntity(
                                status = ConnectionStatus.CONNECTED,
                                versionsUrl = "https://test-platform.com/ocpi/versions",
                                auth =
                                        Auth(
                                                tokenA = "test-token-a",
                                                tokenB = "test-token-b",
                                                tokenC = "test-token-c"
                                        )
                        )
                testPlatform = platformRepository.save(testPlatform)

                // Create a test role
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
        }

        @Test
        fun `getPartyVersions should return versions when platform exists`() {
                val result = versionsService.getPartyVersions("DE", "TST")

                assertThat(result.first).isTrue()
                // Note: The actual versions list depends on the HTTP client response
                // In a real test, you might want to mock the HTTP client or use a test server
        }

        @Test
        fun `getPartyVersions should return false and empty list when platform does not exist`() {
                val result = versionsService.getPartyVersions("XX", "XXX")

                assertThat(result.first).isFalse()
                assertThat(result.second).isEmpty()
        }

        @Test
        fun `getPartyVersions should return false and empty list when role exists but platform is null`() {
                val orphanRole =
                        RoleEntity(
                                platformID = 99999L, // Non-existent platform ID
                                role = Role.EMSP,
                                businessDetails =
                                        snc.openchargingnetwork.node.models.ocpi.BusinessDetails(
                                                name = "Orphan Company",
                                                website = "https://orphan.com"
                                        ),
                                partyID = "ORP",
                                countryCode = "US"
                        )
                roleRepository.save(orphanRole)

                val result = versionsService.getPartyVersions("US", "ORP")

                assertThat(result.first).isFalse()
                assertThat(result.second).isEmpty()
        }

        @Test
        fun `getPartyVersions should handle case insensitive party ID and country code`() {
                val lowercaseRole =
                        RoleEntity(
                                platformID = testPlatform.id!!,
                                role = Role.CPO,
                                businessDetails =
                                        snc.openchargingnetwork.node.models.ocpi.BusinessDetails(
                                                name = "Lowercase Company",
                                                website = "https://lowercase.com"
                                        ),
                                partyID = "low",
                                countryCode = "us"
                        )
                roleRepository.save(lowercaseRole)

                val result = versionsService.getPartyVersions("US", "LOW")

                assertThat(result.first).isTrue()
        }

        @Test
        fun `getPartyVersions should handle multiple roles for same party`() {
                val role2 =
                        RoleEntity(
                                platformID = testPlatform.id!!,
                                role = Role.EMSP,
                                businessDetails =
                                        snc.openchargingnetwork.node.models.ocpi.BusinessDetails(
                                                name = "Test Company EMSP",
                                                website = "https://test-emsp.com"
                                        ),
                                partyID = "TST",
                                countryCode = "DE"
                        )
                roleRepository.save(role2)

                val result = versionsService.getPartyVersions("DE", "TST")

                assertThat(result.first).isTrue()
                // Should return the same platform since both roles point to the same platform
        }
}
