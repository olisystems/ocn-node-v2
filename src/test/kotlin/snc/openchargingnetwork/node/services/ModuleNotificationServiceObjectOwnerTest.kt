package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

class ModuleNotificationServiceObjectOwnerTest {

    private lateinit var service: ModuleNotificationService

    @BeforeEach
    fun setup() {
        service =
            ModuleNotificationService(
                platformRepo = Mockito.mock(PlatformRepository::class.java),
                roleRepo = Mockito.mock(RoleRepository::class.java),
                endpointRepo = Mockito.mock(EndpointRepository::class.java),
                httpClientComponent = Mockito.mock(HttpClientComponent::class.java),
                routingService = Mockito.mock(RoutingService::class.java),
                ocnRulesService = Mockito.mock(OcnRulesService::class.java),
                nodeProperties =
                    NodeProperties().apply {
                        countryCode = "DE"
                        partyId = "BAN"
                    }
            )
    }

    @Test
    fun `resolveObjectOwner reads country and party from url path`() {
        val owner =
            service.resolveObjectOwner(
                request(
                    urlPath = "/DE/ABC/LDEMO001",
                    sender = BasicRole("BAN", "DE"),
                    body = null
                )
            )

        assertThat(owner).isEqualTo(BasicRole("ABC", "DE"))
    }

    @Test
    fun `resolveObjectOwner falls back to body country_code and party_id`() {
        val owner =
            service.resolveObjectOwner(
                request(
                    urlPath = null,
                    sender = BasicRole("BAN", "DE"),
                    body =
                        mapOf(
                            "country_code" to "DE",
                            "party_id" to "ABC",
                            "id" to "TLOCAL001"
                        )
                )
            )

        assertThat(owner).isEqualTo(BasicRole("ABC", "DE"))
    }

    @Test
    fun `resolveObjectOwner prefers path over body`() {
        val owner =
            service.resolveObjectOwner(
                request(
                    urlPath = "DE/ABE/TOKEN1",
                    sender = BasicRole("BAN", "DE"),
                    body = mapOf("country_code" to "DE", "party_id" to "ABC")
                )
            )

        assertThat(owner).isEqualTo(BasicRole("ABE", "DE"))
    }

    private fun request(
        urlPath: String?,
        sender: BasicRole,
        body: Any?
    ): OcpiRequestVariables {
        return OcpiRequestVariables(
            module = ModuleID.LOCATIONS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = HttpMethod.PUT,
            headers =
                OcnHeaders(
                    authorization = "Token test",
                    requestID = "req",
                    correlationID = "corr",
                    sender = sender,
                    receiver = BasicRole("BAN", "DE")
                ),
            body = body,
            urlPath = urlPath
        )
    }
}
