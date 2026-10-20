package snc.openchargingnetwork.node.tools

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TracingTest {

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `masks Authorization token keeping scheme and last four characters`() {
        assertEquals("Token ****bg==", maskAuthorization("Token ZGVmYXVsdC10b2tlbg=="))
    }

    @Test
    fun `masks short credentials completely`() {
        assertEquals("Token ****", maskAuthorization("Token abc123"))
        assertEquals("****", maskAuthorization("abc123"))
    }

    @Test
    fun `masks Authorization header regardless of case and leaves other headers untouched`() {
        val masked =
                mapOf(
                                "authorization" to "Token 0123456789abcdef",
                                X_REQUEST_ID to "rid",
                                "OCN-Signature" to null
                        )
                        .withMaskedAuthorization()

        assertEquals("Token ****cdef", masked["authorization"])
        assertEquals("rid", masked[X_REQUEST_ID])
        assertNull(masked["OCN-Signature"])
    }

    @Test
    fun `putTraceContext from OCPI headers sets ids and parties`() {
        putTraceContext(
                OcnHeaders(
                        authorization = "Token x",
                        requestID = "rid-1",
                        correlationID = "cid-1",
                        sender = BasicRole("AAA", "DE"),
                        receiver = BasicRole("BBB", "NL")
                )
        )

        assertEquals("rid-1", MDC.get(TraceKeys.REQUEST_ID))
        assertEquals("cid-1", MDC.get(TraceKeys.CORRELATION_ID))
        assertEquals("DE/AAA", MDC.get(TraceKeys.OCPI_FROM))
        assertEquals("NL/BBB", MDC.get(TraceKeys.OCPI_TO))
    }

    @Test
    fun `putTraceContext removes keys whose value is blank`() {
        MDC.put(TraceKeys.CORRELATION_ID, "stale")

        putTraceContext(requestId = "rid", correlationId = "", from = null, to = null)

        assertNull(MDC.get(TraceKeys.CORRELATION_ID))
        assertEquals("rid", MDC.get(TraceKeys.REQUEST_ID))
    }

    @Test
    fun `withMdc restores the previous value`() {
        MDC.put(TraceKeys.OUT_REQUEST_ID, "outer")

        withMdc(TraceKeys.OUT_REQUEST_ID, "inner") {
            assertEquals("inner", MDC.get(TraceKeys.OUT_REQUEST_ID))
        }
        assertEquals("outer", MDC.get(TraceKeys.OUT_REQUEST_ID))

        MDC.remove(TraceKeys.OUT_REQUEST_ID)
        withMdc(TraceKeys.OUT_REQUEST_ID, "only") {}
        assertNull(MDC.get(TraceKeys.OUT_REQUEST_ID))
    }

    @Test
    fun `withCorrelationId reuses the correlation id of the current request`() {
        MDC.put(TraceKeys.CORRELATION_ID, "inbound-cid")

        val used = withCorrelationId { it }

        assertEquals("inbound-cid", used)
    }

    @Test
    fun `withCorrelationId generates one when none is in context and exposes it in MDC`() {
        var seenInMdc: String? = null
        val used = withCorrelationId {
            seenInMdc = MDC.get(TraceKeys.CORRELATION_ID)
            it
        }

        assertNotNull(seenInMdc)
        assertEquals(used, seenInMdc)
        assertNull(MDC.get(TraceKeys.CORRELATION_ID))
    }
}
