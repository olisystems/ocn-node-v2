package snc.openchargingnetwork.node.controllers

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.mockito.Mockito
import org.mockito.kotlin.*
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandler
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.components.OcpiResponseHandler
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.TariffsController
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.services.ModuleNotificationService

class TariffsControllerTest {

    private lateinit var requestHandlerBuilder: OcpiRequestHandlerBuilder
    private lateinit var moduleNotificationService: ModuleNotificationService
    private lateinit var nodeProperties: NodeProperties

    private lateinit var controller: TariffsController

    @BeforeEach
    fun setup() {
        requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        moduleNotificationService = Mockito.mock(ModuleNotificationService::class.java)
        nodeProperties =
            NodeProperties().apply {
                countryCode = "DE"
                partyId = "SNC"
                apiPrefix = "ocn-node/v3"
                url = "https://node.example.com"
            }
        controller = TariffsController(requestHandlerBuilder, moduleNotificationService, nodeProperties)
    }

    @Test
    fun `putClientOwnedTariff does not broadcast when addressed to different node`() {
        val authorization = "Token test"
        val signature: String? = null
        val requestId = "req-2"
        val correlationId = "cor-2"
        val fromCountry = "DE"
        val fromParty = "CPO"
        val toCountry = "NL" // different from nodeProperties.countryCode
        val toParty = "ABC" // different from nodeProperties.partyId
        val countryCode = "DE"
        val partyID = "CPO"
        val tariffID = "T124"

        val tariff =
            Tariff(
                countryCode = countryCode,
                partyID = partyID,
                id = tariffID,
                currency = "EUR",
                elements =
                    listOf(
                        TariffElement(
                            priceComponents =
                                listOf(
                                    PriceComponent(
                                        type = TariffDimensionType.ENERGY,
                                        price = 0.25f,
                                        stepSize = 1
                                    )
                                )
                        )
                    ),
                lastUpdated = "2020-01-01T00:00:00Z"
            )

        @Suppress("UNCHECKED_CAST")
        val handler = Mockito.mock(OcpiRequestHandler::class.java) as OcpiRequestHandler<Unit>

        @Suppress("UNCHECKED_CAST")
        val responseHandler = Mockito.mock(OcpiResponseHandler::class.java) as OcpiResponseHandler<Unit>

        Mockito.`when`(requestHandlerBuilder.build<Unit>(any<OcpiRequestVariables>()))
            .thenReturn(handler)
        Mockito.`when`(handler.forwardDefault()).thenReturn(responseHandler)
        Mockito.`when`(responseHandler.getResponse())
            .thenReturn(ResponseEntity.ok(OcpiResponse<Unit>(statusCode = OcpiStatus.SUCCESS.code)))

        Mockito.`when`(
            moduleNotificationService.getPartiesToNotifyOfModuleChange(
                moduleId = ModuleID.TARIFFS,
                partyId = fromParty,
                countryCode = fromCountry
            )
        )
            .thenReturn(emptyList())

        val response = controller.putClientOwnedTariff(
            authorization = authorization,
            signature = signature,
            requestID = requestId,
            correlationID = correlationId,
            fromCountryCode = fromCountry,
            fromPartyID = fromParty,
            toCountryCode = toCountry,
            toPartyID = toParty,
            countryCode = countryCode,
            partyID = partyID,
            tariffID = tariffID,
            body = tariff
        )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(0))
            .notifyPartiesOfModuleChangeAsync(
                moduleId = eq(ModuleID.TARIFFS),
                parties = any<Iterable<RoleEntity>>(),
                changedData = eq(tariff),
                urlPath = any<String>(),
                sender = any<BasicRole>()
            )

        verify(requestHandlerBuilder, times(1)).build<Unit>(any<OcpiRequestVariables>())
    }

    @Test
    fun `putClientOwnedTariff broadcasts when addressed to this node and parties exist`() {
        val authorization = "Token test"
        val signature: String? = null
        val requestId = "req-1"
        val correlationId = "cor-1"
        val fromCountry = "DE"
        val fromParty = "CPO"
        val toCountry = "DE"
        val toParty = "SNC"
        val countryCode = "DE"
        val partyID = "CPO"
        val tariffID = "T123"

        val tariff =
            Tariff(
                countryCode = countryCode,
                partyID = partyID,
                id = tariffID,
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

        @Suppress("UNCHECKED_CAST")
        val handler = Mockito.mock(OcpiRequestHandler::class.java) as OcpiRequestHandler<Unit>

        @Suppress("UNCHECKED_CAST")
        val responseHandler = Mockito.mock(OcpiResponseHandler::class.java) as OcpiResponseHandler<Unit>

        Mockito.`when`(requestHandlerBuilder.build<Unit>(any<OcpiRequestVariables>()))
            .thenReturn(handler)
        Mockito.`when`(handler.forwardDefault()).thenReturn(responseHandler)
        Mockito.`when`(responseHandler.getResponse())
            .thenReturn(ResponseEntity.ok(OcpiResponse<Unit>(statusCode = OcpiStatus.SUCCESS.code)))

        val parties =
            listOf(
                RoleEntity(
                    platformID = 1L,
                    role = Role.CPO,
                    businessDetails = BusinessDetails(name = "Test CPO"),
                    partyID = "XYZ",
                    countryCode = "DE"
                )
            )

        Mockito.`when`(
            moduleNotificationService.getPartiesToNotifyOfModuleChange(
                moduleId = ModuleID.TARIFFS,
                partyId = fromParty,
                countryCode = fromCountry
            )
        )
            .thenReturn(parties)

        val response = controller.putClientOwnedTariff(
            authorization = authorization,
            signature = signature,
            requestID = requestId,
            correlationID = correlationId,
            fromCountryCode = fromCountry,
            fromPartyID = fromParty,
            toCountryCode = toCountry,
            toPartyID = toParty,
            countryCode = countryCode,
            partyID = partyID,
            tariffID = tariffID,
            body = tariff
        )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(1))
            .notifyPartiesOfModuleChangeAsync(
                moduleId = eq(ModuleID.TARIFFS),
                parties = eq(parties),
                changedData = eq(tariff),
                urlPath = eq("$countryCode/$partyID/$tariffID"),
                sender = eq(BasicRole(fromParty, fromCountry))
            )

        // For broadcast path, ensure we did not forward
        verify(requestHandlerBuilder, times(0)).build<Unit>(any<OcpiRequestVariables>())
    }
}
