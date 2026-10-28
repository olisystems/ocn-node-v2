package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito
import org.slf4j.MDC
import org.springframework.core.task.SimpleAsyncTaskExecutor
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.entities.Auth
import snc.openchargingnetwork.node.models.entities.PlatformEntity
import snc.openchargingnetwork.node.models.entities.RoleEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.BusinessDetails
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.Role
import snc.openchargingnetwork.node.repositories.EndpointRepository
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.repositories.RoleRepository
import java.util.Optional

/** A broadcast the node starts itself gets one correlation ID shared by every receiver. */
class BroadcastCorrelationIdTest {

    private val platformRepo = Mockito.mock(PlatformRepository::class.java)
    private val routingService = Mockito.mock(RoutingService::class.java)
    private val sentCorrelationIds = mutableListOf<String>()

    private val service =
        ModuleNotificationService(
            platformRepo = platformRepo,
            roleRepo = Mockito.mock(RoleRepository::class.java),
            endpointRepo = Mockito.mock(EndpointRepository::class.java),
            httpClientComponent = Mockito.mock(HttpClientComponent::class.java),
            routingService = routingService,
            ocnRulesService = Mockito.mock(OcnRulesService::class.java),
            nodeProperties = NodeProperties().apply {
                countryCode = "DE"
                partyId = "BAN"
            },
            taskExecutor = SimpleAsyncTaskExecutor()
        )

    @AfterEach
    fun reset() {
        MDC.clear()
        sentCorrelationIds.clear()
    }

    private fun stubReceivers() {
        Mockito.`when`(platformRepo.findById(anyLong()))
            .thenReturn(Optional.of(PlatformEntity(auth = Auth(tokenB = "token-b"))))
        Mockito.doAnswer {
            val request = it.getArgument<OcpiRequestVariables>(0)
            sentCorrelationIds += request.headers.correlationID
            "http://receiver" to request.headers
        }.`when`(routingService).prepareLocalPlatformRequest(any(), anyBoolean())
    }

    private fun parties() =
        listOf("ABC", "ABE", "ABF").map {
            RoleEntity(1L, Role.EMSP, BusinessDetails(it), it, "DE")
        }

    private fun broadcast() =
        service.notifyPartiesOfModuleChange(
            ModuleID.LOCATIONS, parties(), mapOf("id" to "LOC1"), "DE/OLI/LOC1", BasicRole("BAN", "DE")
        )

    @Test
    fun `node-originated broadcast shares one correlation ID`() {
        stubReceivers()

        broadcast()

        assertThat(sentCorrelationIds).hasSize(3)
        assertThat(sentCorrelationIds.toSet()).hasSize(1)
        assertThat(MDC.get("correlation_id")).isNull()
    }

    @Test
    fun `broadcast keeps the inbound correlation ID`() {
        stubReceivers()
        MDC.put("correlation_id", "inbound-cid")

        broadcast()

        assertThat(sentCorrelationIds).containsOnly("inbound-cid")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> any(): T {
        Mockito.any<T>()
        return null as T
    }
}
