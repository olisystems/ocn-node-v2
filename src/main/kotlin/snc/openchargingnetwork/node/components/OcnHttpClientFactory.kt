package snc.openchargingnetwork.node.components

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import java.net.ConnectException
import org.slf4j.LoggerFactory
import snc.openchargingnetwork.node.config.NodeProperties

/**
 * Builds the Ktor client the node uses for every outbound call (OCPI forwarding, platform
 * credentials, hub client info, registry).
 *
 * The node reaches its parties through public hostnames, so a TCP connect occasionally stalls
 * longer than Ktor CIO's implicit 5 s connect timeout. A connect failure means the request never
 * left the node, so it is safe to retry it for every HTTP method. Anything after the connection was
 * established (read/socket timeouts, HTTP status codes) is NOT retried and is not given a timeout
 * here, so behaviour past the connect stays unchanged.
 */
object OcnHttpClientFactory {

    private val logger = LoggerFactory.getLogger(OcnHttpClientFactory::class.java)

    fun create(
            properties: NodeProperties,
            engine: HttpClientEngine = defaultEngine(properties)
    ): HttpClient =
            HttpClient(engine) {
                // Only the connect phase is bounded, the engine default (5 s) is too tight.
                install(HttpTimeout) { connectTimeoutMillis = properties.httpConnectTimeoutMs }

                if (properties.httpConnectRetries > 0) {
                    install(HttpRequestRetry) {
                        // Ktor retries 5xx responses by default; a response means the request
                        // reached the server, so it must never be re-sent (POST/PATCH would repeat).
                        retryIf(maxRetries = properties.httpConnectRetries) { _, _ -> false }
                        retryOnExceptionIf(maxRetries = properties.httpConnectRetries) { _, cause ->
                            cause.isConnectFailure()
                        }
                        delayMillis(respectRetryAfterHeader = false) { retry ->
                            properties.httpConnectRetryDelayMs * retry
                        }
                        modifyRequest { request ->
                            logger.warn(
                                    "Connect to {} failed ({}), retry {}/{}",
                                    request.url,
                                    cause?.message,
                                    retryCount,
                                    properties.httpConnectRetries
                            )
                        }
                    }
                }
            }

    private fun defaultEngine(properties: NodeProperties): HttpClientEngine =
            CIO.create { endpoint.connectTimeout = properties.httpConnectTimeoutMs }

    internal fun Throwable.isConnectFailure(): Boolean =
            generateSequence(this) { it.cause }
                    .take(5)
                    .any { it is ConnectTimeoutException || it is ConnectException }
}
