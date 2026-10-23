package snc.openchargingnetwork.node.config

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import snc.openchargingnetwork.node.tools.TraceKeys
import snc.openchargingnetwork.node.tools.X_CORRELATION_ID
import snc.openchargingnetwork.node.tools.X_REQUEST_ID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequestTracingFilterTest {

    private val filter = RequestTracingFilter()

    @AfterEach
    fun clearMdc() = MDC.clear()

    private fun ocpiRequest() =
            MockHttpServletRequest("PUT", "/ocn/ocpi/receiver/2.2.1/locations/DE/AAA/LOC1").apply {
                addHeader(X_REQUEST_ID, "rid-1")
                addHeader(X_CORRELATION_ID, "cid-1")
                addHeader("OCPI-from-country-code", "DE")
                addHeader("OCPI-from-party-id", "AAA")
                addHeader("OCPI-to-country-code", "NL")
                addHeader("OCPI-to-party-id", "BBB")
            }

    @Test
    fun `populates MDC from request headers during the chain and clears it afterwards`() {
        var seen: Map<String, String>? = null
        val chain = FilterChain { _, _ -> seen = MDC.getCopyOfContextMap() }

        filter.doFilter(ocpiRequest(), MockHttpServletResponse(), chain)

        assertEquals(
                mapOf(
                        TraceKeys.REQUEST_ID to "rid-1",
                        TraceKeys.CORRELATION_ID to "cid-1",
                        TraceKeys.OCPI_FROM to "DE/AAA",
                        TraceKeys.OCPI_TO to "NL/BBB"
                ),
                seen
        )
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
    }

    @Test
    fun `clears MDC when the chain throws`() {
        val chain = FilterChain { _, _ -> throw IllegalStateException("boom") }

        assertThrows<IllegalStateException> {
            filter.doFilter(ocpiRequest(), MockHttpServletResponse(), chain)
        }
        assertTrue(MDC.getCopyOfContextMap().isNullOrEmpty())
    }

    @Test
    fun `echoes X-Request-ID and X-Correlation-ID on the response`() {
        val response = MockHttpServletResponse()

        filter.doFilter(ocpiRequest(), response) { _, _ -> }

        assertEquals("rid-1", response.getHeader(X_REQUEST_ID))
        assertEquals("cid-1", response.getHeader(X_CORRELATION_ID))
    }

    @Test
    fun `does not invent ids or parties that the request did not carry`() {
        val response = MockHttpServletResponse()
        var seen: Map<String, String>? = null
        val request =
                MockHttpServletRequest("GET", "/ocn/ocpi/versions").apply {
                    addHeader("OCPI-from-country-code", "DE")
                }

        filter.doFilter(request, response) { _, _ -> seen = MDC.getCopyOfContextMap() }

        assertTrue(seen.isNullOrEmpty())
        assertNull(response.getHeader(X_REQUEST_ID))
        assertNull(response.getHeader(X_CORRELATION_ID))
    }
}
