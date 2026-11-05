package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.mockito.Mockito.inOrder
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.components.OcpiRequestHandler
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.components.OcpiResponseHandler
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.BroadcastDelivery
import snc.openchargingnetwork.node.models.BroadcastDelivery.Outcome
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.exceptions.OcpiBroadcastDeliveryException
import snc.openchargingnetwork.node.models.ocpi.*

class NodeObjectRoutingServiceTest {

    private lateinit var requestHandlerBuilder: OcpiRequestHandlerBuilder
    private lateinit var moduleNotificationService: ModuleNotificationService
    private lateinit var routingService: RoutingService
    private lateinit var requestHandler: OcpiRequestHandler<Unit>
    private lateinit var responseHandler: OcpiResponseHandler<Unit>
    private lateinit var service: NodeObjectRoutingService

    @BeforeEach
    @Suppress("UNCHECKED_CAST")
    fun setup() {
        requestHandlerBuilder = Mockito.mock(OcpiRequestHandlerBuilder::class.java)
        moduleNotificationService = Mockito.mock(ModuleNotificationService::class.java)
        routingService = Mockito.mock(RoutingService::class.java)
        requestHandler = Mockito.mock(OcpiRequestHandler::class.java) as OcpiRequestHandler<Unit>
        responseHandler = Mockito.mock(OcpiResponseHandler::class.java) as OcpiResponseHandler<Unit>

        service =
            NodeObjectRoutingService(
                requestHandlerBuilder,
                moduleNotificationService,
                routingService,
                NodeProperties().apply {
                    countryCode = "DE"
                    partyId = "BAN"
                }
            )

        whenever(requestHandlerBuilder.build<Unit>(any<OcpiRequestVariables>())).thenReturn(requestHandler)
        whenever(requestHandler.validateIncomingForBroadcast()).thenReturn(requestHandler)
        whenever(requestHandler.validateIncomingFromOcnForBroadcast(any())).thenReturn(requestHandler)
        whenever(requestHandler.forwardDefault()).thenReturn(responseHandler)
        whenever(requestHandler.forwardFromOcn(any())).thenReturn(responseHandler)
        whenever(responseHandler.getResponse()).thenReturn(successResponse())
        whenever(responseHandler.getResponseWithAllHeaders()).thenReturn(successResponse())
        whenever(moduleNotificationService.broadcastObjectRequest(any())).thenReturn(emptyList())
    }

    @Test
    fun `answers success when every receiver accepted the broadcast`() {
        val request = hubRequest()
        whenever(moduleNotificationService.broadcastObjectRequest(request))
            .thenReturn(listOf(delivered("ABC"), delivered("ABE")))

        val response = service.route<Unit>(request)

        assertThat(response.body?.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)
    }

    @Test
    fun `a single receiver's rejection reaches the sender with its own code`() {
        val request = hubRequest()
        whenever(moduleNotificationService.broadcastObjectRequest(request))
            .thenReturn(listOf(BroadcastDelivery(BasicRole("ERO", "DE"), Outcome.REJECTED, 200, 2001, "Missing uid")))

        val error = assertThrows<OcpiBroadcastDeliveryException> { service.route<Unit>(request) }

        assertThat(error.statusCode).isEqualTo(2001)
        assertThat(error.message)
            .contains("reached 0 of 1 receivers")
            .contains("DE/ERO rejected it (HTTP 200, OCPI 2001: Missing uid)")
    }

    @Test
    fun `a failure among several receivers is a 3000 naming each one`() {
        val request = hubRequest()
        whenever(moduleNotificationService.broadcastObjectRequest(request))
            .thenReturn(
                listOf(
                    delivered("ABC"),
                    BroadcastDelivery(BasicRole("ERO", "DE"), Outcome.REJECTED, 500, 3000, "Eround is down"),
                    BroadcastDelivery(BasicRole("XYZ", "DE"), Outcome.UNREACHABLE, message = "could not be reached")
                )
            )

        val error = assertThrows<OcpiBroadcastDeliveryException> { service.route<Unit>(request) }

        assertThat(error.statusCode).isEqualTo(OcpiStatus.SERVER_ERROR.code)
        assertThat(error.message)
            .contains("reached 1 of 3 receivers")
            .contains("DE/ERO rejected it (HTTP 500, OCPI 3000: Eround is down)")
            .contains("DE/XYZ could not be reached")
            .doesNotContain("DE/ABC")
    }

    @Test
    fun `receivers that have not answered yet make it a timeout on the forwarded request`() {
        val request = hubRequest()
        whenever(moduleNotificationService.broadcastObjectRequest(request))
            .thenReturn(listOf(delivered("ABC"), BroadcastDelivery(BasicRole("XYZ", "DE"), Outcome.PENDING)))

        val error = assertThrows<OcpiBroadcastDeliveryException> { service.route<Unit>(request) }

        assertThat(error.statusCode).isEqualTo(OcpiStatus.HUB_REQUEST_TIMEOUT.code)
        assertThat(error.message).contains("DE/XYZ has not answered yet")
    }

    @Test
    fun `a rejection by the connected hub handler reaches the sender`() {
        val request = hubRequest()
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(true)
        whenever(responseHandler.getResponse())
            .thenReturn(ResponseEntity.ok(OcpiResponse(statusCode = 3000, statusMessage = "tariff store down")))
        whenever(moduleNotificationService.broadcastObjectRequest(request)).thenReturn(listOf(delivered("ABC")))

        val error = assertThrows<OcpiBroadcastDeliveryException> { service.route<Unit>(request) }

        assertThat(error.statusCode).isEqualTo(OcpiStatus.SERVER_ERROR.code)
        assertThat(error.message).contains("DE/BAN rejected it (HTTP 200, OCPI 3000: tariff store down)")
    }

    private fun hubRequest() = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("CPO", "DE"))

    private fun delivered(partyId: String) =
        BroadcastDelivery(BasicRole(partyId, "DE"), Outcome.DELIVERED, 200, OcpiStatus.SUCCESS.code)

    @Test
    fun `broadcasts hub-addressed objects and forwards to connected handler when sender is another party`() {
        val request = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("CPO", "DE"))
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(true)

        val response = service.route<Unit>(request)

        assertThat(response.body?.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)
        verify(requestHandler).validateIncomingForBroadcast()
        verify(requestHandler).forwardDefault()
        verify(moduleNotificationService).broadcastObjectRequest(request)
    }

    @Test
    fun `broadcasts hub-addressed objects without forwarding when sender is the hub itself`() {
        val request = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("BAN", "DE"))
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(true)

        val response = service.route<Unit>(request)

        assertThat(response.body?.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)
        verify(requestHandler).validateIncomingForBroadcast()
        verify(requestHandler, never()).forwardDefault()
        verify(moduleNotificationService).broadcastObjectRequest(request)
    }

    @Test
    fun `broadcasts hub-addressed objects when no same-identity party is connected`() {
        val request = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("CPO", "DE"))
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(false)

        val response = service.route<Unit>(request)

        assertThat(response.body?.statusCode).isEqualTo(OcpiStatus.SUCCESS.code)
        verify(requestHandler).validateIncomingForBroadcast()
        verify(requestHandler, never()).forwardDefault()
        verify(moduleNotificationService).broadcastObjectRequest(request)
    }

    @Test
    fun `keeps normal routing for objects addressed to another party`() {
        val request = objectRequest(receiver = BasicRole("MSP", "NL"), sender = BasicRole("CPO", "DE"))

        service.route<Unit>(request)

        verify(requestHandler).forwardDefault()
        verify(routingService, never()).isRoleConnected(any())
        verify(moduleNotificationService, never()).broadcastObjectRequest(any())
    }

    @Test
    fun `validates the sending node before broadcasting an inter-node object`() {
        val request = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("CPO", "DE"))
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(false)

        service.route<Unit>(request, sendingNodeSignature = "node-signature")

        verify(requestHandler).validateIncomingFromOcnForBroadcast("node-signature")
        verify(requestHandler, never()).validateIncomingForBroadcast()
        verify(moduleNotificationService).broadcastObjectRequest(request)
    }

    @Test
    fun `forwards inter-node object to connected handler with signature before broadcasting`() {
        val request = objectRequest(receiver = BasicRole("BAN", "DE"), sender = BasicRole("CPO", "DE"))
        whenever(routingService.isRoleConnected(request.headers.receiver)).thenReturn(true)

        service.route<Unit>(request, sendingNodeSignature = "node-signature")

        val ordered = inOrder(requestHandler, moduleNotificationService)
        ordered.verify(requestHandler).validateIncomingFromOcnForBroadcast("node-signature")
        ordered.verify(requestHandler).forwardFromOcn("node-signature")
        ordered.verify(moduleNotificationService).broadcastObjectRequest(request)
        verify(requestHandler, never()).forwardDefault()
    }

    @Test
    fun `forwards non-hub-addressed inter-node object with signature`() {
        val request = objectRequest(receiver = BasicRole("MSP", "NL"), sender = BasicRole("CPO", "DE"))

        service.route<Unit>(request, sendingNodeSignature = "node-signature")

        verify(requestHandler).forwardFromOcn("node-signature")
        verify(responseHandler).getResponseWithAllHeaders()
        verify(routingService, never()).isRoleConnected(any())
        verify(moduleNotificationService, never()).broadcastObjectRequest(any())
    }

    private fun objectRequest(receiver: BasicRole, sender: BasicRole): OcpiRequestVariables {
        return OcpiRequestVariables(
            module = ModuleID.LOCATIONS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = HttpMethod.PUT,
            headers =
                OcnHeaders(
                    authorization = "Token test",
                    requestID = "request",
                    correlationID = "correlation",
                    sender = sender,
                    receiver = receiver
                ),
            urlPath = "/DE/CPO/location-1",
            body = mapOf("id" to "location-1")
        )
    }

    private fun successResponse(): ResponseEntity<OcpiResponse<Unit>> {
        return ResponseEntity.ok(OcpiResponse(statusCode = OcpiStatus.SUCCESS.code))
    }
}
