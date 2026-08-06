package snc.openchargingnetwork.node.config

import kotlin.test.Test
import kotlin.test.assertEquals

class NodePropertiesTest {
    @Test
    fun `outbound OCPI request timeout allows slow credentials handshakes`() {
        assertEquals(60000, NodeProperties().httpRequestTimeoutMillis)
    }
}
