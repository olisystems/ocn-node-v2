package snc.openchargingnetwork.node.components

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.OcnMessageHeaders
import snc.openchargingnetwork.node.models.SyncedHttpResponse
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.tools.CurlLogger
import snc.openchargingnetwork.node.tools.TraceKeys
import snc.openchargingnetwork.node.tools.X_CORRELATION_ID
import snc.openchargingnetwork.node.tools.X_REQUEST_ID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpClientComponentTraceTest {

    private class Sent(val headers: Map<String, String>, val mdc: Map<String, String>)

    private val sent = mutableListOf<Sent>()

    private val client =
            object : HttpClientComponent(NodeProperties().apply { logCurlCommands = true }) {
                override fun sendHttpRequest(
                        endpoint: String,
                        method: HttpMethod,
                        body: Any?,
                        headers: Map<String, String>,
                        queryParams: Map<String, String>
                ): SyncedHttpResponse {
                    sent += Sent(headers, MDC.getCopyOfContextMap() ?: emptyMap())
                    return SyncedHttpResponse(
                            statusCode = HttpStatusCode.OK,
                            headers = Headers.Empty,
                            contentType = ContentType.Application.Json,
                            contentLength = null,
                            body = """{"status_code":1000,"data":[],"timestamp":"2026-01-01T00:00:00Z"}"""
                    )
                }
            }

    @AfterEach
    fun reset() {
        MDC.clear()
        sent.clear()
    }

    private fun ocpiRequest() =
            OcpiRequestVariables(
                    module = ModuleID.LOCATIONS,
                    interfaceRole = InterfaceRole.RECEIVER,
                    method = HttpMethod.GET,
                    headers =
                            OcnHeaders(
                                    authorization = "Token c2VjcmV0LXRva2VuLXZhbHVl",
                                    requestID = "out-rid",
                                    correlationID = "cid-1",
                                    sender = BasicRole("AAA", "DE"),
                                    receiver = BasicRole("BBB", "NL")
                            )
            )

    @Test
    fun `forwarded OCPI request exposes its outbound request id in MDC only while in flight`() {
        MDC.put(TraceKeys.REQUEST_ID, "inbound-rid")

        client.makeOcpiRequest<Any>("https://platform/locations", ocpiRequest().headers, ocpiRequest())

        assertEquals("out-rid", sent.single().mdc[TraceKeys.OUT_REQUEST_ID])
        assertEquals("inbound-rid", sent.single().mdc[TraceKeys.REQUEST_ID])
        assertEquals("cid-1", sent.single().headers[X_CORRELATION_ID])
        assertNull(MDC.get(TraceKeys.OUT_REQUEST_ID))
    }

    @Test
    fun `OCN message exposes the transport request id in MDC`() {
        client.postOcnMessage<Any>("https://remote-node", OcnMessageHeaders("transport-rid", "sig"), "{}")

        assertEquals("transport-rid", sent.single().mdc[TraceKeys.OUT_REQUEST_ID])
    }

    @Test
    fun `versions call reuses the correlation id of the current request`() {
        MDC.put(TraceKeys.CORRELATION_ID, "inbound-cid")

        client.getVersions("https://platform/versions", "token")

        val headers = sent.single().headers
        assertEquals("inbound-cid", headers[X_CORRELATION_ID])
        assertEquals(headers[X_REQUEST_ID], sent.single().mdc[TraceKeys.OUT_REQUEST_ID])
    }

    @Test
    fun `versions call outside a request generates a correlation id and logs it`() {
        client.getVersionDetailSafely()

        val generated = sent.single().headers[X_CORRELATION_ID]
        assertTrue(!generated.isNullOrBlank())
        assertEquals(generated, sent.single().mdc[TraceKeys.CORRELATION_ID])
        assertNull(MDC.get(TraceKeys.CORRELATION_ID))
    }

    @Test
    fun `curl log masks the Authorization header`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val curlLogger = LoggerFactory.getLogger(CurlLogger::class.java) as Logger
        curlLogger.addAppender(appender)
        try {
            client.makeOcpiRequest<Any>("https://platform/locations", ocpiRequest().headers, ocpiRequest())
        } finally {
            curlLogger.detachAppender(appender)
        }

        val line = appender.list.single().formattedMessage
        assertTrue(line.contains("Authorization: Token ****bHVl"), line)
        assertFalse(line.contains("c2VjcmV0LXRva2VuLXZhbHVl"))
    }

    // The canned response is not a VersionDetail; only the outgoing headers matter here.
    private fun HttpClientComponent.getVersionDetailSafely() =
            runCatching { getVersionDetail("https://platform/versions/2.2.1", "token") }
}
