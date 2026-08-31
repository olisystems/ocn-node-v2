package snc.openchargingnetwork.node.controllers

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.HCIProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.HubClientInfoController
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.ModuleNotificationService
import snc.openchargingnetwork.node.services.RoutingService
import snc.openchargingnetwork.node.services.WalletService

class HubClientInfoControllerTest {

    @Test
    fun `updateClientInfo rejects a missing signature when verification is enabled`() {
        val walletService = Mockito.mock(WalletService::class.java)
        val controller = controller(walletService = walletService, signaturesEnabled = true)

        assertThrows<OcpiClientInvalidParametersException> {
            controller.updateClientInfo(
                fromCountryCode = "DE",
                fromPartyID = "BAN",
                signature = null,
                body = "{}"
            )
        }
        verify(walletService, never()).verify(any(), any(), any())
    }

    @Test
    fun `updateClientInfo rejects a blank signature when verification is enabled`() {
        val walletService = Mockito.mock(WalletService::class.java)
        val controller = controller(walletService = walletService, signaturesEnabled = true)

        assertThrows<OcpiClientInvalidParametersException> {
            controller.updateClientInfo(
                fromCountryCode = "DE",
                fromPartyID = "BAN",
                signature = "  ",
                body = "{}"
            )
        }
        verify(walletService, never()).verify(any(), any(), any())
    }

    private fun controller(
        walletService: WalletService,
        signaturesEnabled: Boolean
    ): HubClientInfoController {
        val nodeProperties =
            NodeProperties().apply {
                dev = false
                signatures = signaturesEnabled
            }
        return HubClientInfoController(
            Mockito.mock(RoutingService::class.java),
            Mockito.mock(HubClientInfoService::class.java),
            Mockito.mock(OcpiRequestHandlerBuilder::class.java),
            HCIProperties(),
            nodeProperties,
            walletService,
            Mockito.mock(HttpClientComponent::class.java),
            Mockito.mock(ModuleNotificationService::class.java)
        )
    }
}
