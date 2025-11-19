package snc.openchargingnetwork.node.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.LocationsController
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.services.ModuleNotificationService

class LocationsControllerTest {

    private lateinit var requestHandlerBuilder: OcpiRequestHandlerBuilder
    private lateinit var moduleNotificationService: ModuleNotificationService
    private lateinit var nodeProperties: NodeProperties

    private lateinit var controller: LocationsController

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
        controller =
            LocationsController(
                requestHandlerBuilder,
                moduleNotificationService,
                nodeProperties
            )
    }

    private fun okUnitResponse(): ResponseEntity<OcpiResponse<Unit>> {
        return ResponseEntity.ok(OcpiResponse<Unit>(statusCode = OcpiStatus.SUCCESS.code))
    }

    private fun partiesList(): List<RoleEntity> {
        return listOf(
                RoleEntity(
                        platformID = 1L,
                        role = Role.EMSP,
                        businessDetails = BusinessDetails(name = "Notify EMSP"),
                        partyID = "EM1",
                        countryCode = "DE"
                )
        )
    }

    @Test
    fun `patchClientOwnedLocation broadcasts with correct urlPath`() {
        val authorization = "Token test"
        val signature: String? = null
        val requestId = "req-4"
        val correlationId = "cor-4"
        val fromCountry = "DE"
        val fromParty = "CPO"
        val toCountry = "DE"
        val toParty = "SNC"
        val countryCode = "DE"
        val partyID = "CPO"
        val locationID = "LOC-4"

        val body: Map<String, Any> = mapOf("name" to "New Name")

        Mockito.`when`(
                        moduleNotificationService.getPartiesToNotifyOfModuleChange(
                                moduleId = ModuleID.LOCATIONS,
                                partyId = fromParty,
                                countryCode = fromCountry
                        )
                )
                .thenReturn(partiesList())

        val response =
                controller.patchClientOwnedLocation(
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
                        locationID = locationID,
                        body = body
                )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(1))
                .notifyPartiesOfModuleChangeAsync(
                        eq(ModuleID.LOCATIONS),
                        any<Iterable<RoleEntity>>(),
                        any(),
                        eq("$countryCode/$partyID/$locationID"),
                        any<BasicRole>()
                )

        verify(requestHandlerBuilder, times(0)).build<Unit>(any<OcpiRequestVariables>())
    }

    @Test
    fun `putClientOwnedConnector broadcasts with correct urlPath`() {
        val authorization = "Token test"
        val signature: String? = null
        val requestId = "req-3"
        val correlationId = "cor-3"
        val fromCountry = "DE"
        val fromParty = "CPO"
        val toCountry = "DE"
        val toParty = "SNC"
        val countryCode = "DE"
        val partyID = "CPO"
        val locationID = "LOC-3"
        val evseUID = "EVSE-2"
        val connectorID = "2"

        val body =
                Connector(
                        id = connectorID,
                        standard = ConnectorType.IEC_62196_T2,
                        format = ConnectorFormat.SOCKET,
                        powerType = PowerType.AC_3_PHASE,
                        maxVoltage = 400,
                        maxAmperage = 32,
                        lastUpdated = "2020-01-01T00:00:00Z"
                )

        Mockito.`when`(
                        moduleNotificationService.getPartiesToNotifyOfModuleChange(
                                moduleId = ModuleID.LOCATIONS,
                                partyId = fromParty,
                                countryCode = fromCountry
                        )
                )
                .thenReturn(partiesList())

        val response =
                controller.putClientOwnedConnector(
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
                        locationID = locationID,
                        evseUID = evseUID,
                        connectorID = connectorID,
                        body = body
                )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(1))
                .notifyPartiesOfModuleChangeAsync(
                        eq(ModuleID.LOCATIONS),
                        any<Iterable<RoleEntity>>(),
                        any(),
                        eq("$countryCode/$partyID/$locationID/$evseUID/$connectorID"),
                        any<BasicRole>()
                )

        verify(requestHandlerBuilder, times(0)).build<Unit>(any<OcpiRequestVariables>())
    }

    @Test
    fun `putClientOwnedEvse broadcasts with correct urlPath`() {
        val authorization = "Token test"
        val signature: String? = null
        val requestId = "req-2"
        val correlationId = "cor-2"
        val fromCountry = "DE"
        val fromParty = "CPO"
        val toCountry = "DE"
        val toParty = "SNC"
        val countryCode = "DE"
        val partyID = "CPO"
        val locationID = "LOC-2"
        val evseUID = "EVSE-1"

        val connector =
                Connector(
                        id = "1",
                        standard = ConnectorType.IEC_62196_T2,
                        format = ConnectorFormat.SOCKET,
                        powerType = PowerType.AC_3_PHASE,
                        maxVoltage = 230,
                        maxAmperage = 16,
                        lastUpdated = "2020-01-01T00:00:00Z"
                )
        val body =
                Evse(
                        uid = evseUID,
                        status = EvseStatus.AVAILABLE,
                        connectors = listOf(connector),
                        lastUpdated = "2020-01-01T00:00:00Z"
                )

        Mockito.`when`(
                        moduleNotificationService.getPartiesToNotifyOfModuleChange(
                                moduleId = ModuleID.LOCATIONS,
                                partyId = fromParty,
                                countryCode = fromCountry
                        )
                )
                .thenReturn(partiesList())

        val response =
                controller.putClientOwnedEvse(
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
                        locationID = locationID,
                        evseUID = evseUID,
                        body = body
                )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(1))
                .notifyPartiesOfModuleChangeAsync(
                        eq(ModuleID.LOCATIONS),
                        any<Iterable<RoleEntity>>(),
                        any(),
                        eq("$countryCode/$partyID/$locationID/$evseUID"),
                        any<BasicRole>()
                )

        verify(requestHandlerBuilder, times(0)).build<Unit>(any<OcpiRequestVariables>())
    }

    @Test
    fun `putClientOwnedLocation broadcasts with correct urlPath`() {
        //        mockForwardChain()

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
        val locationID = "LOC-1"

        val body =
                Location(
                        countryCode = countryCode,
                        partyID = partyID,
                        id = locationID,
                        publish = true,
                        address = "Street 1",
                        city = "City",
                        country = "DE",
                        coordinates = GeoLocation(latitude = "0.0", longitude = "0.0"),
                        lastUpdated = "2020-01-01T00:00:00Z"
                )

        Mockito.`when`(
                        moduleNotificationService.getPartiesToNotifyOfModuleChange(
                                moduleId = ModuleID.LOCATIONS,
                                partyId = fromParty,
                                countryCode = fromCountry
                        )
                )
                .thenReturn(partiesList())

        val response =
                controller.putClientOwnedLocation(
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
                        locationID = locationID,
                        body = body
                )

        assertThat(response.statusCode.value()).isEqualTo(200)
        assertThat(response.body!!.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)

        verify(moduleNotificationService, times(1))
                .notifyPartiesOfModuleChangeAsync(
                        eq(ModuleID.LOCATIONS),
                        any<Iterable<RoleEntity>>(),
                        any(),
                        eq("$countryCode/$partyID/$locationID"),
                        any<BasicRole>()
                )

        verify(requestHandlerBuilder, times(0)).build<Unit>(any<OcpiRequestVariables>())
    }
}
