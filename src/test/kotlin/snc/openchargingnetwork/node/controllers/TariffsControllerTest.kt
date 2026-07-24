package snc.openchargingnetwork.node.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.TariffsController
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.services.NodeObjectRoutingService

class TariffsControllerTest {

    private lateinit var requestHandlerBuilder: OcpiRequestHandlerBuilder
    private lateinit var nodeObjectRoutingService: NodeObjectRoutingService
    private lateinit var controller: TariffsController

    @BeforeEach
    fun setup() {
        requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        nodeObjectRoutingService = Mockito.mock(NodeObjectRoutingService::class.java)
        controller = TariffsController(requestHandlerBuilder, nodeObjectRoutingService)
        whenever(nodeObjectRoutingService.route<Unit>(any(), anyOrNull()))
            .thenReturn(ResponseEntity.ok(OcpiResponse(statusCode = OcpiStatus.SUCCESS.code)))
    }

    @Test
    fun `put tariff delegates object routing`() {
        val tariff =
            Tariff(
                countryCode = "DE",
                partyID = "CPO",
                id = "T123",
                currency = "EUR",
                elements =
                    listOf(
                        TariffElement(
                            priceComponents =
                                listOf(
                                    PriceComponent(
                                        type = TariffDimensionType.ENERGY,
                                        price = 0.3f,
                                        stepSize = 1
                                    )
                                )
                        )
                    ),
                lastUpdated = "2020-01-01T00:00:00Z"
            )

        controller.putClientOwnedTariff(
            authorization = "Token test",
            requestID = "request",
            correlationID = "correlation",
            fromCountryCode = "DE",
            fromPartyID = "CPO",
            toCountryCode = "DE",
            toPartyID = "BAN",
            countryCode = "DE",
            partyID = "CPO",
            tariffID = "T123",
            body = tariff
        )

        val captor = argumentCaptor<OcpiRequestVariables>()
        verify(nodeObjectRoutingService).route<Unit>(captor.capture(), anyOrNull())
        assertThat(captor.firstValue.method).isEqualTo(HttpMethod.PUT)
        assertThat(captor.firstValue.urlPath).isEqualTo("/DE/CPO/T123")
        assertThat(captor.firstValue.body).isEqualTo(tariff)
    }

    @Test
    fun `delete tariff delegates a bodyless object request`() {
        controller.deleteClientOwnedTariff(
            authorization = "Token test",
            requestID = "request",
            correlationID = "correlation",
            fromCountryCode = "DE",
            fromPartyID = "CPO",
            toCountryCode = "DE",
            toPartyID = "BAN",
            countryCode = "DE",
            partyID = "CPO",
            tariffID = "T123"
        )

        val captor = argumentCaptor<OcpiRequestVariables>()
        verify(nodeObjectRoutingService).route<Unit>(captor.capture(), anyOrNull())
        assertThat(captor.firstValue.method).isEqualTo(HttpMethod.DELETE)
        assertThat(captor.firstValue.body).isNull()
    }
}
