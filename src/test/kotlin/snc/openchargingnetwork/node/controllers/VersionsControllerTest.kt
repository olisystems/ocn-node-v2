package snc.openchargingnetwork.node.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.verify
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.VersionsController
import snc.openchargingnetwork.node.services.RoutingService
import snc.openchargingnetwork.node.services.VersionsService

class VersionsControllerTest {

    @Test
    fun `local versions uses checkSenderKnown and advertises apiPrefixPublic`() {
        val requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        val versionsService = Mockito.mock(VersionsService::class.java)
        val routingService = Mockito.mock(RoutingService::class.java)
        val nodeProperties =
            NodeProperties().apply {
                url = "https://node.example.com"
                apiPrefix = "/ocn"
                apiPrefixPublic = "/api/v2/public"
                countryCode = "DE"
                partyId = "BAN"
            }
        val controller =
            VersionsController(
                requestHandlerBuilder,
                versionsService,
                routingService,
                nodeProperties
            )

        val response =
            controller.getVersions(
                authorization = "Token abc",
                signature = null,
                requestID = null,
                correlationID = null,
                fromCountryCode = null,
                fromPartyID = null,
                toCountryCode = null,
                toPartyID = null
            )

        verify(routingService).checkSenderKnown("Token abc")
        assertThat(response.body?.data?.single()?.url)
            .isEqualTo("https://node.example.com/ocn/api/v2/public/ocpi/2.2.1")
    }
}
