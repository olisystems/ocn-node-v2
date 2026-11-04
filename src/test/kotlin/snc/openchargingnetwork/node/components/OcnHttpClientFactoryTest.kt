package snc.openchargingnetwork.node.components

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.net.ConnectException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import snc.openchargingnetwork.node.config.NodeProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OcnHttpClientFactoryTest {

    private val properties =
            NodeProperties().apply {
                httpConnectRetries = 2
                httpConnectRetryDelayMs = 1
            }

    private val calls = AtomicInteger()

    // the test instance may be shared across methods (PER_CLASS lifecycle), so count from zero each time
    @BeforeEach
    fun resetCalls() {
        calls.set(0)
        properties.httpConnectRetries = 2
    }

    private fun engine(handler: (attempt: Int) -> Unit) = MockEngine {
        handler(calls.incrementAndGet())
        respond("ok", HttpStatusCode.OK)
    }

    @Test
    fun `defaults give the connect phase 20 s and two retries`() {
        val defaults = NodeProperties()
        assertEquals(20_000, defaults.httpConnectTimeoutMs)
        assertEquals(2, defaults.httpConnectRetries)
    }

    @Test
    fun `connect timeout twice then success is retried and succeeds on the third attempt`() {
        val client =
                OcnHttpClientFactory.create(
                        properties,
                        engine { attempt ->
                            if (attempt < 3) throw ConnectTimeoutException("Connect timeout", null)
                        }
                )

        val body = runBlocking { client.get("http://localhost/x").bodyAsText() }

        assertEquals("ok", body)
        assertEquals(3, calls.get())
    }

    @Test
    fun `connection refused is retried like a connect timeout`() {
        val client =
                OcnHttpClientFactory.create(
                        properties,
                        engine { attempt -> if (attempt < 2) throw ConnectException("refused") }
                )

        runBlocking { client.get("http://localhost/x") }

        assertEquals(2, calls.get())
    }

    @Test
    fun `connect failing on every attempt surfaces the original exception after one try plus two retries`() {
        val client =
                OcnHttpClientFactory.create(
                        properties,
                        engine { throw ConnectTimeoutException("Connect timeout", null) }
                )

        assertFailsWith<ConnectTimeoutException> {
            runBlocking { client.get("http://localhost/x") }
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `retries can be disabled`() {
        val client =
                OcnHttpClientFactory.create(
                        properties.apply { httpConnectRetries = 0 },
                        engine { throw ConnectTimeoutException("Connect timeout", null) }
                )

        assertFailsWith<ConnectTimeoutException> {
            runBlocking { client.get("http://localhost/x") }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `an HTTP error status is returned once and never retried`() {
        val client =
                OcnHttpClientFactory.create(
                        properties,
                        MockEngine {
                            calls.incrementAndGet()
                            respond("boom", HttpStatusCode.InternalServerError)
                        }
                )

        val status = runBlocking { client.get("http://localhost/x").status }

        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a non connect failure is not retried`() {
        val client =
                OcnHttpClientFactory.create(
                        properties,
                        engine { throw IllegalStateException("socket reset") }
                )

        assertFailsWith<IllegalStateException> {
            runBlocking { client.get("http://localhost/x") }
        }
        assertEquals(1, calls.get())
    }
}
