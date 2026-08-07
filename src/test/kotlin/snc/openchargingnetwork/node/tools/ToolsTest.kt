package snc.openchargingnetwork.node.tools

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolsTest {

    @Test
    fun `timestamp truncates fractional seconds to milliseconds`() {
        val timestamp = getTimestamp(Instant.parse("2026-08-07T08:38:37.158864260Z"))

        assertEquals("2026-08-07T08:38:37.158Z", timestamp)
    }

    @Test
    fun `timestamp omits fractional seconds when milliseconds are zero`() {
        val timestamp = getTimestamp(Instant.parse("2026-08-07T08:38:37Z"))

        assertEquals("2026-08-07T08:38:37Z", timestamp)
    }
}
