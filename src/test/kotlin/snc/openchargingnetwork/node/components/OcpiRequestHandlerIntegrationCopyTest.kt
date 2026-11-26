package snc.openchargingnetwork.node.components

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.config.HaasProperties
import snc.openchargingnetwork.node.config.IntegrationsProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.Receiver
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.services.AsyncTaskService
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.IntegrationCopyOutboxService
import snc.openchargingnetwork.node.services.IntegrationsRoutingService
import snc.openchargingnetwork.node.services.RegistryService
import snc.openchargingnetwork.node.services.RoutingService
import snc.openchargingnetwork.node.services.WalletService

/** Integration copies are stored only after the main forward was accepted. */
class OcpiRequestHandlerIntegrationCopyTest {

    private val routingService = Mockito.mock(RoutingService::class.java)
    private val registryService = Mockito.mock(RegistryService::class.java)
    private val httpClient = Mockito.mock(HttpClientComponent::class.java)
    private val responseHandlerBuilder = Mockito.mock(OcpiResponseHandlerBuilder::class.java)
    private val protocolTransformService = Mockito.mock(OcpiProtocolTransformService::class.java)
    private val outbox = Mockito.mock(IntegrationCopyOutboxService::class.java)

    private val request =
        OcpiRequestVariables(
            module = ModuleID.SESSIONS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = HttpMethod.PUT,
            urlPath = "/DE/ABC/s1",
            headers =
                OcnHeaders(
                    authorization = "Token sender-token",
                    requestID = "rid-1",
                    correlationID = "cid-1",
                    sender = BasicRole("ABC", "DE"),
                    receiver = BasicRole("MSP", "DE")
                ),
            body = mapOf("id" to "s1")
        )

    // One test instance serves every test of the class (junit-platform.properties).
    @BeforeEach
    fun resetMocks() {
        Mockito.reset(routingService, httpClient, responseHandlerBuilder, protocolTransformService, outbox)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handler(): OcpiRequestHandler<Unit> {
        whenever(routingService.getReceiverType(any())).thenReturn(Receiver.LOCAL)
        whenever(routingService.prepareLocalPlatformRequest(any(), any())).thenReturn(Pair("http://msp.test/sessions", request.headers))
        whenever(protocolTransformService.adaptOutboundRequest(any())).thenReturn(request)
        whenever(protocolTransformService.adaptInboundResponse<Unit>(any(), any())).thenAnswer { it.getArgument(1) }
        whenever(responseHandlerBuilder.build<Unit>(any(), any(), any(), anyOrNull()))
            .thenReturn(Mockito.mock(OcpiResponseHandler::class.java) as OcpiResponseHandler<Unit>)
        return OcpiRequestHandler(
            request,
            routingService,
            registryService,
            httpClient,
            Mockito.mock(HubClientInfoService::class.java),
            Mockito.mock(WalletService::class.java),
            Mockito.mock(AsyncTaskService::class.java),
            responseHandlerBuilder,
            IntegrationsRoutingService(registryService, IntegrationsProperties()),
            outbox,
            protocolTransformService,
            NodeProperties().apply { signatures = false },
            HaasProperties(),
            Mockito.mock(ApplicationEventPublisher::class.java),
            // Unconfined runs the launched dispatch inline, so it can be verified without waiting.
            CoroutineScope(Dispatchers.Unconfined)
        )
    }

    private fun mainForwardAnswers(ocpiStatus: Int) {
        whenever(httpClient.makeOcpiRequest<Unit>(any<String>(), any<OcnHeaders>(), any<OcpiRequestVariables>()))
            .thenReturn(OcpiHttpResponse(statusCode = 200, headers = emptyMap(), body = OcpiResponse(ocpiStatus)))
    }

    @Test
    fun `an accepted main forward stores the copy and starts a first attempt`() {
        val handler = handler()
        mainForwardAnswers(1000)

        handler.forwardIntegrationsAsync(ModuleID.SESSIONS)
        verify(outbox, never()).enqueue(any(), anyOrNull())

        handler.forwardDefault()

        val copy = argumentCaptor<OcpiRequestVariables>()
        val order = inOrder(httpClient, outbox)
        order.verify(httpClient).makeOcpiRequest<Unit>(any<String>(), any<OcnHeaders>(), any<OcpiRequestVariables>())
        order.verify(outbox).enqueue(copy.capture(), eq("NOT_PRESENTED"))
        order.verify(outbox).dispatchDue()
        assertThat(copy.firstValue.headers.receiver).isEqualTo(BasicRole("OLI", "DE"))
        assertThat(copy.firstValue.body).isEqualTo(request.body)
    }

    @Test
    fun `no copy when the receiver rejects the main forward`() {
        val handler = handler()
        mainForwardAnswers(3000)

        handler.forwardIntegrationsAsync(ModuleID.SESSIONS).forwardDefault()

        verify(outbox, never()).enqueue(any(), anyOrNull())
        verify(outbox, never()).dispatchDue()
    }

    @Test
    fun `no copy when the main forward cannot be sent`() {
        val handler = handler()
        whenever(httpClient.makeOcpiRequest<Unit>(any<String>(), any<OcnHeaders>(), any<OcpiRequestVariables>()))
            .thenAnswer { throw IOException("Connection refused") }

        assertThatThrownBy { handler.forwardSenderIntegrationsAsync(ModuleID.SESSIONS).forwardDefault() }
            .isInstanceOf(IOException::class.java)

        verify(outbox, never()).enqueue(any(), anyOrNull())
    }

    @Test
    fun `a main forward that ran elsewhere releases the copy only when it was accepted`() {
        val handler = handler().forwardIntegrationsAsync(ModuleID.SESSIONS)

        handler.enqueueIntegrationCopiesIfAccepted(ResponseEntity.ok(OcpiResponse<Unit>(statusCode = 3000)))
        verify(outbox, never()).enqueue(any(), anyOrNull())

        handler.enqueueIntegrationCopiesIfAccepted(ResponseEntity.ok(OcpiResponse<Unit>(statusCode = 1000)))
        verify(outbox).enqueue(any(), anyOrNull())
    }

    @Test
    fun `a copy that cannot be stored does not change the main response`() {
        val handler = handler()
        mainForwardAnswers(1000)
        doThrow(IllegalStateException("database is down")).whenever(outbox).enqueue(any(), anyOrNull())

        val responseHandler = handler.forwardIntegrationsAsync(ModuleID.SESSIONS).forwardDefault()

        assertThat(responseHandler).isNotNull()
        verify(outbox, never()).dispatchDue()
    }
}
