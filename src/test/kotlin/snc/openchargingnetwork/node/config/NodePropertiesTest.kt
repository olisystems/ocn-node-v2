package snc.openchargingnetwork.node.config

import kotlin.test.Test
import kotlin.test.assertEquals

class NodePropertiesTest {
    @Test
    fun `outbound request timeout expires before the ingress timeout`() {
        assertEquals(55_000, NodeProperties().httpRequestTimeoutMillis)
    }
}
