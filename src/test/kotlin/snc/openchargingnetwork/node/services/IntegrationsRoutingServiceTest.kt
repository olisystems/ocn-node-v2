package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import snc.openchargingnetwork.node.config.IntegrationsProperties
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID

class IntegrationsRoutingServiceTest {

    private val registryService = Mockito.mock(RegistryService::class.java)
    private val sender = BasicRole("ABC", "DE")

    @Test
    fun `without configuration the targets are the ones that used to be hardcoded`() {
        val service = IntegrationsRoutingService(registryService, IntegrationsProperties())

        listOf(ModuleID.CDRS, ModuleID.TOKENS, ModuleID.SESSIONS).forEach {
            assertThat(service.getIntegrationReceivingParties(it, sender)).containsExactly(BasicRole("OLI", "DE"))
            assertThat(service.getSenderIntegrationReceivingParties(it, sender)).containsExactly(BasicRole("OLI", "DE"))
        }
        listOf(ModuleID.LOCATIONS, ModuleID.TARIFFS).forEach {
            assertThat(service.getIntegrationReceivingParties(it, sender)).containsExactly(BasicRole("BAN", "DE"))
        }
        assertThat(service.getIntegrationReceivingParties(ModuleID.COMMANDS, sender)).isEmpty()
        assertThat(service.getIntegrationReceivingParties(ModuleID.SESSIONS, BasicRole("OLI", "DE"))).isEmpty()
    }

    @Test
    fun `targets come from the properties`() {
        val properties =
            IntegrationsProperties().apply {
                targets.sessions = listOf("nl/xyz", " DE/OLI ")
                targets.cdrs = listOf("")
            }
        val service = IntegrationsRoutingService(registryService, properties)

        assertThat(service.getIntegrationReceivingParties(ModuleID.SESSIONS, sender))
            .containsExactly(BasicRole("XYZ", "NL"), BasicRole("OLI", "DE"))
        assertThat(service.getIntegrationReceivingParties(ModuleID.CDRS, sender)).isEmpty()
    }

    @Test
    fun `a malformed target fails at startup`() {
        val properties = IntegrationsProperties().apply { targets.tokens = listOf("DEOLI") }

        assertThatThrownBy { IntegrationsRoutingService(registryService, properties) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
