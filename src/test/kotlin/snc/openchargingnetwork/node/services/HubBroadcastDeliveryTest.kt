package snc.openchargingnetwork.node.services

import java.net.ConnectException
import java.util.Collections
import java.util.Optional
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever
import org.slf4j.MDC
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.MdcTaskDecorator
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.BroadcastDelivery.Outcome
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.BusinessDetails
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.Role
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository

/** A hub broadcast waits for its receivers and reports what each one answered. */
class HubBroadcastDeliveryTest {

    private val platformRepo = Mockito.mock(PlatformRepository::class.java)
    private val routingService = Mockito.mock(RoutingService::class.java)
    private val httpClient = Mockito.mock(HttpClientComponent::class.java)
    private val sentCorrelationIds = Collections.synchronizedList(mutableListOf<String>())

    private val service =
        Mockito.spy(
            ModuleNotificationService(
                platformRepo = platformRepo,
                roleRepo = Mockito.mock(RoleRepository::class.java),
                endpointRepo = Mockito.mock(EndpointRepository::class.java),
                httpClientComponent = httpClient,
                routingService = routingService,
                ocnRulesService = Mockito.mock(OcnRulesService::class.java),
                nodeProperties = NodeProperties().apply {
                    countryCode = "DE"
                    partyId = "BAN"
                    broadcastWaitMillis = 300
                },
                // Same MDC copying as the production @Async executor.
                taskExecutor = SimpleAsyncTaskExecutor().apply { setTaskDecorator(MdcTaskDecorator()) }
            )
        )

    @AfterEach
    fun reset() {
        MDC.clear()
        sentCorrelationIds.clear()
    }

    private fun receivers(vararg partyIds: String) {
        doReturn(partyIds.map { RoleEntity(1L, Role.CPO, BusinessDetails(it), it, "DE") })
            .whenever(service)
            .getPartiesToNotifyOfModuleChange(any(), anyOrNull(), any(), any())
        whenever(platformRepo.findById(anyLong()))
            .thenReturn(Optional.of(PlatformEntity(auth = Auth(tokenB = "token-b"))))
        Mockito.doAnswer {
            val request = it.getArgument<OcpiRequestVariables>(0)
            sentCorrelationIds += request.headers.correlationID
            "http://${request.headers.receiver.id.lowercase()}.internal" to request.headers
        }.`when`(routingService).prepareLocalPlatformRequest(any(), anyBoolean())
        whenever(httpClient.makeOcpiRequest<Unit>(any<String>(), any<OcnHeaders>(), any<OcpiRequestVariables>()))
            .thenAnswer {
                when (it.getArgument<OcpiRequestVariables>(2).headers.receiver.id) {
                    "ERO" -> OcpiHttpResponse<Unit>(200, emptyMap(), OcpiResponse(statusCode = 3000, statusMessage = "Eround is down"))
                    "XYZ" -> throw ConnectException("Connection refused: http://xyz.internal")
                    "SLO" -> {
                        Thread.sleep(1500)
                        OcpiHttpResponse<Unit>(200, emptyMap(), OcpiResponse(statusCode = 1000))
                    }
                    else -> OcpiHttpResponse<Unit>(200, emptyMap(), OcpiResponse(statusCode = 1000))
                }
            }
    }

    private fun tokenPush() =
        OcpiRequestVariables(
            module = ModuleID.TOKENS,
            interfaceRole = InterfaceRole.RECEIVER,
            method = HttpMethod.PUT,
            headers = OcnHeaders(
                authorization = "Token test",
                requestID = "request",
                correlationID = "inbound-cid",
                sender = BasicRole("OLI", "DE"),
                receiver = BasicRole("BAN", "DE")
            ),
            urlPath = "/DE/OLI/TOKEN1",
            body = mapOf("uid" to "TOKEN1")
        )

    @Test
    fun `reports each receiver's answer`() {
        receivers("ABC", "ERO", "XYZ")

        val deliveries = service.broadcastObjectRequest(tokenPush()).associateBy { it.receiver.id }

        assertThat(deliveries["ABC"]!!.outcome).isEqualTo(Outcome.DELIVERED)
        assertThat(deliveries["ERO"]!!.outcome).isEqualTo(Outcome.REJECTED)
        assertThat(deliveries["ERO"]!!.ocpiStatus).isEqualTo(3000)
        assertThat(deliveries["ERO"]!!.message).isEqualTo("Eround is down")
        assertThat(deliveries["XYZ"]!!.outcome).isEqualTo(Outcome.UNREACHABLE)
        // The sender only learns that the receiver was unreachable, not where it lives.
        assertThat(deliveries["XYZ"]!!.describe()).isEqualTo("DE/XYZ could not be reached")
    }

    @Test
    fun `does not wait past the deadline for a slow receiver`() {
        receivers("ABC", "SLO")

        val started = System.nanoTime()
        val deliveries = service.broadcastObjectRequest(tokenPush()).associateBy { it.receiver.id }
        val tookMillis = (System.nanoTime() - started) / 1_000_000

        assertThat(tookMillis).isLessThan(1200)
        assertThat(deliveries["ABC"]!!.outcome).isEqualTo(Outcome.DELIVERED)
        assertThat(deliveries["SLO"]!!.outcome).isEqualTo(Outcome.PENDING)
    }

    @Test
    fun `parallel deliveries keep the sender's correlation ID`() {
        receivers("ABC", "ABE", "ABF")
        MDC.put("correlation_id", "inbound-cid")

        service.broadcastObjectRequest(tokenPush())

        assertThat(sentCorrelationIds).hasSize(3).containsOnly("inbound-cid")
    }

    @Test
    fun `parallel deliveries share one correlation ID when there is no inbound one`() {
        receivers("ABC", "ABE", "ABF")

        service.broadcastObjectRequest(tokenPush())

        assertThat(sentCorrelationIds).hasSize(3)
        assertThat(sentCorrelationIds.toSet()).hasSize(1)
    }
}
