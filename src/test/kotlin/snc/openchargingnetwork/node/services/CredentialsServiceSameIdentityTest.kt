package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.repositories.*

class CredentialsServiceSameIdentityTest {

    @Test
    fun `post credentials accepts a registered handler with the node identity`() {
        val platformRepo = Mockito.mock(PlatformRepository::class.java)
        val roleRepo = Mockito.mock(RoleRepository::class.java)
        val endpointRepo = Mockito.mock(EndpointRepository::class.java)
        val networkClientInfoRepo = Mockito.mock(NetworkClientInfoRepository::class.java)
        val ocnRulesRepo = Mockito.mock(OcnRulesListRepository::class.java)
        val registryService = Mockito.mock(RegistryService::class.java)
        val httpClient = Mockito.mock(HttpClientComponent::class.java)
        val properties =
            NodeProperties().apply {
                countryCode = "DE"
                partyId = "BAN"
                apiPrefix = "ocn-v2"
                url = "https://node.example.com"
            }
        val platform = PlatformEntity(auth = Auth(tokenA = "token-a"), id = 1L)
        val service =
            CredentialsService(
                platformRepo,
                roleRepo,
                endpointRepo,
                networkClientInfoRepo,
                ocnRulesRepo,
                properties,
                registryService,
                httpClient
            )
        val handlerRole =
            CredentialsRole(
                role = Role.HUB,
                businessDetails = BusinessDetails(name = "DE BAN"),
                partyID = "BAN",
                countryCode = "DE"
            )

        whenever(platformRepo.findByAuth_TokenA("token-a")).thenReturn(platform)
        whenever(httpClient.getVersions("https://handler.example.com/versions", "handler-token"))
            .thenReturn(listOf(Version("2.2.1", "https://handler.example.com/2.2.1")))
        whenever(httpClient.getVersionDetail("https://handler.example.com/2.2.1", "handler-token"))
            .thenReturn(VersionDetail("2.2.1", emptyList()))
        whenever(registryService.isRoleKnown(BasicRole("BAN", "DE"))).thenReturn(true)

        val response =
            service.postCredentials(
                tokenA = "token-a",
                body =
                    Credentials(
                        token = "handler-token",
                        url = "https://handler.example.com/versions",
                        roles = listOf(handlerRole)
                    )
            )

        assertThat(platform.status).isEqualTo(ConnectionStatus.CONNECTED)
        assertThat(response.data?.roles?.single()?.countryCode).isEqualTo("DE")
        assertThat(response.data?.roles?.single()?.partyID).isEqualTo("BAN")
        verify(registryService).isRoleKnown(BasicRole("BAN", "DE"))
    }
}
