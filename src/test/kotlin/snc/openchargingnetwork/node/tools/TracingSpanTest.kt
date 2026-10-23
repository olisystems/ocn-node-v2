package snc.openchargingnetwork.node.tools

import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.MDC
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracingSpanTest {

    companion object {
        @JvmField
        @RegisterExtension
        val otel: OpenTelemetryExtension = OpenTelemetryExtension.create()
    }

    @AfterEach
    fun clearMdc() = MDC.clear()

    private fun inSpan(block: () -> Unit): SpanData {
        val span = otel.openTelemetry.getTracer("test").spanBuilder("server").startSpan()
        span.makeCurrent().use { block() }
        span.end()
        return otel.spans.single()
    }

    private fun SpanData.attr(key: String): String? = attributes.get(stringKey(key))

    @Test
    fun `putTraceContext sets ocpi attributes on the current span and skips blank values`() {
        val span = inSpan { putTraceContext(requestId = "rid-1", correlationId = "cid-1", from = "DE/AAA", to = " ") }

        assertEquals("rid-1", span.attr(SpanKeys.REQUEST_ID))
        assertEquals("cid-1", span.attr(SpanKeys.CORRELATION_ID))
        assertEquals("DE/AAA", span.attr(SpanKeys.FROM))
        assertNull(span.attr(SpanKeys.TO))
    }

    @Test
    fun `embedded OCN headers override the transport ids and add the message request id`() {
        val span =
                inSpan {
                    putTraceContext(requestId = "hop-rid", correlationId = null, from = null, to = null)
                    putTraceContext(
                            OcnHeaders(
                                    authorization = "Token x",
                                    requestID = "rid-1",
                                    correlationID = "cid-1",
                                    sender = BasicRole("AAA", "DE"),
                                    receiver = BasicRole("BBB", "NL")
                            )
                    )
                    putOcnMessageRequestId("hop-rid")
                }

        assertEquals("rid-1", span.attr(SpanKeys.REQUEST_ID))
        assertEquals("cid-1", span.attr(SpanKeys.CORRELATION_ID))
        assertEquals("DE/AAA", span.attr(SpanKeys.FROM))
        assertEquals("NL/BBB", span.attr(SpanKeys.TO))
        assertEquals("hop-rid", span.attr(SpanKeys.OCN_MESSAGE_REQUEST_ID))
        assertEquals("hop-rid", MDC.get(TraceKeys.OCN_MESSAGE_REQUEST_ID))
    }

    @Test
    fun `tagOcpiRoute sets module and interface role`() {
        val span = inSpan { tagOcpiRoute("locations", "receiver") }

        assertEquals("locations", span.attr(SpanKeys.MODULE))
        assertEquals("receiver", span.attr(SpanKeys.INTERFACE_ROLE))
    }

    @Test
    fun `recordOutboundRequest adds a forward event with the out request id and url`() {
        val span = inSpan { recordOutboundRequest("out-1", "https://cpo.example/ocpi/2.2.1/locations") }

        val event = span.events.single()
        assertEquals(SpanKeys.FORWARD_EVENT, event.name)
        assertEquals("out-1", event.attributes.get(stringKey(SpanKeys.OUT_REQUEST_ID)))
        assertEquals("https://cpo.example/ocpi/2.2.1/locations", event.attributes.get(stringKey(SpanKeys.URL_FULL)))
    }

    @Test
    fun `recordOutboundRequest without an out request id adds no event`() {
        val span = inSpan { recordOutboundRequest(null, "https://node.example/ocn/client-info") }

        assertTrue(span.events.isEmpty())
    }

    @Test
    fun `helpers are no-ops without an active span`() {
        putTraceContext(requestId = "rid-1", correlationId = "cid-1", from = null, to = null)
        recordOutboundRequest("out-1", "https://cpo.example")

        assertFalse(otel.spans.any())
        assertEquals("rid-1", MDC.get(TraceKeys.REQUEST_ID))
    }
}
