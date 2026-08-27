package snc.openchargingnetwork.node.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OcpiHttpResponseTest {

    @Test
    fun `lowercased header from an HTTP2 upstream is found under its canonical name`() {
        val headers = mapOf("location" to "https://node/ocpi/receiver/2.2.1/cdrs/1").toCaseInsensitiveHeaders()

        assertEquals("https://node/ocpi/receiver/2.2.1/cdrs/1", headers["Location"])
    }

    @Test
    fun `capitalised header from an HTTP1_1 upstream is still found`() {
        val headers = mapOf("Location" to "https://node/ocpi/receiver/2.2.1/cdrs/1").toCaseInsensitiveHeaders()

        assertEquals("https://node/ocpi/receiver/2.2.1/cdrs/1", headers["Location"])
        assertEquals("https://node/ocpi/receiver/2.2.1/cdrs/1", headers["location"])
    }

    @Test
    fun `pagination headers are case-insensitive in both directions`() {
        val headers = mapOf(
            "x-total-count" to "42",
            "X-Limit" to "10",
            "link" to "<https://node/page/2>; rel=\"next\""
        ).toCaseInsensitiveHeaders()

        assertEquals("42", headers["X-Total-Count"])
        assertEquals("10", headers["x-limit"])
        assertEquals("<https://node/page/2>; rel=\"next\"", headers["Link"])
    }

    @Test
    fun `absent header still returns null`() {
        val headers = mapOf("content-type" to "application/json").toCaseInsensitiveHeaders()

        assertNull(headers["Location"])
    }

    @Test
    fun `signed values pick up headers regardless of wire casing`() {
        val response = OcpiHttpResponse<Unit>(
            statusCode = 200,
            headers = mapOf(
                "location" to "https://node/cdrs/1",
                "x-total-count" to "42",
                "x-limit" to "10",
                "link" to "<https://node/page/2>; rel=\"next\""
            ).toCaseInsensitiveHeaders()
        )

        val signed = response.toSignedValues().headers!!

        assertEquals("https://node/cdrs/1", signed.location)
        assertEquals("42", signed.totalCount)
        assertEquals("10", signed.limit)
        assertEquals("<https://node/page/2>; rel=\"next\"", signed.link)
    }
}
