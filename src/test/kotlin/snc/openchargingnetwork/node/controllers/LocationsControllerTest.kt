package snc.openchargingnetwork.node.controllers

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.LocationsController
import snc.openchargingnetwork.node.models.ocpi.*
import snc.openchargingnetwork.node.services.NodeObjectRoutingService

class LocationsControllerTest {

    private lateinit var requestHandlerBuilder: OcpiRequestHandlerBuilder
    private lateinit var nodeObjectRoutingService: NodeObjectRoutingService
    private lateinit var controller: LocationsController

    @BeforeEach
    fun setup() {
        requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        nodeObjectRoutingService = Mockito.mock(NodeObjectRoutingService::class.java)
        controller = LocationsController(requestHandlerBuilder, nodeObjectRoutingService)
        whenever(nodeObjectRoutingService.route<Unit>(org.mockito.kotlin.any()))
            .thenReturn(successResponse())
    }

    @Test
    fun `put connector delegates the complete object request to node routing`() {
        val connector =
            Connector(
                id = "2",
                standard = ConnectorType.IEC_62196_T2,
                format = ConnectorFormat.SOCKET,
                powerType = PowerType.AC_3_PHASE,
                maxVoltage = 400,
                maxAmperage = 32,
                lastUpdated = "2020-01-01T00:00:00Z"
            )

        controller.putClientOwnedConnector(
            authorization = "Token test",
            requestID = "request",
            correlationID = "correlation",
            fromCountryCode = "DE",
            fromPartyID = "CPO",
            toCountryCode = "DE",
            toPartyID = "BAN",
            countryCode = "DE",
            partyID = "CPO",
            locationID = "LOC-1",
            evseUID = "EVSE-1",
            connectorID = "2",
            body = connector
        )

        val request = captureRequest()
        assertThat(request.method).isEqualTo(HttpMethod.PUT)
        assertThat(request.module).isEqualTo(ModuleID.LOCATIONS)
        assertThat(request.urlPath).isEqualTo("/DE/CPO/LOC-1/EVSE-1/2")
        assertThat(request.headers.receiver).isEqualTo(BasicRole("BAN", "DE"))
        assertThat(request.body).isEqualTo(connector)
    }

    @Test
    fun `patch location delegates patch semantics to node routing`() {
        val body: Map<String, Any> = mapOf("name" to "New Name")

        controller.patchClientOwnedLocation(
            authorization = "Token test",
            requestID = "request",
            correlationID = "correlation",
            fromCountryCode = "DE",
            fromPartyID = "CPO",
            toCountryCode = "DE",
            toPartyID = "BAN",
            countryCode = "DE",
            partyID = "CPO",
            locationID = "LOC-1",
            body = body
        )

        val request = captureRequest()
        assertThat(request.method).isEqualTo(HttpMethod.PATCH)
        assertThat(request.urlPath).isEqualTo("/DE/CPO/LOC-1")
        assertThat(request.body).isEqualTo(body)
    }

    private fun captureRequest(): OcpiRequestVariables {
        val captor = argumentCaptor<OcpiRequestVariables>()
        verify(nodeObjectRoutingService).route<Unit>(captor.capture())
        return captor.firstValue
    }

    private fun successResponse(): ResponseEntity<OcpiResponse<Unit>> {
        return ResponseEntity.ok(OcpiResponse(statusCode = OcpiStatus.SUCCESS.code))
    }
}
