package snc.openchargingnetwork.node.services

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RoutingServiceRemoteTraceTest {

    private val receiver = BasicRole("BBB", "NL")
    private val registryService = mock<RegistryService> {
        on { getRemoteNodeUrlOf(receiver) } doReturn "https://remote-node"
    }
    private val walletService = mock<WalletService>()
    private val httpClientComponent = HttpClientComponent(NodeProperties())

    private val routingService =
            RoutingService(
                    platformRepo = mock(),
                    roleRepo = mock(),
                    endpointRepo = mock(),
                    proxyResourceRepo = mock(),
                    registryService = registryService,
                    httpClientComponent = httpClientComponent,
                    walletService = walletService,
                    ocnRulesService = mock()
            )

    @Test
    fun `remote forwarding keeps OCPI ids in the signed body and uses a new transport request id`() {
        whenever(walletService.sign(any())).thenReturn("node-signature")
        val request =
                OcpiRequestVariables(
                        module = ModuleID.LOCATIONS,
                        interfaceRole = InterfaceRole.RECEIVER,
                        method = HttpMethod.PUT,
                        headers =
                                OcnHeaders(
                                        authorization = "Token secret",
                                        requestID = "inbound-rid",
                                        correlationID = "inbound-cid",
                                        sender = BasicRole("AAA", "DE"),
                                        receiver = receiver
                                ),
                        urlPath = "DE/AAA/LOC1"
                )

        val (url, transportHeaders, body) = routingService.prepareRemotePlatformRequest(request)
        val embedded = httpClientComponent.convertToRequestVariables(body).headers

        assertEquals("https://remote-node", url)
        assertEquals("inbound-cid", embedded.correlationID)
        assertEquals("inbound-rid", embedded.requestID)
        assertEquals("", embedded.authorization)
        assertNotEquals("inbound-rid", transportHeaders.requestID)
        assertEquals("node-signature", transportHeaders.signature)
    }
}
