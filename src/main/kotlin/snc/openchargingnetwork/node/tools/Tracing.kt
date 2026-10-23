package snc.openchargingnetwork.node.tools

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import org.slf4j.MDC
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.ocpi.BasicRole

const val X_REQUEST_ID = "X-Request-ID"
const val X_CORRELATION_ID = "X-Correlation-ID"
const val AUTHORIZATION = "Authorization"

object TraceKeys {
    const val CORRELATION_ID = "correlation_id"
    const val REQUEST_ID = "request_id"
    const val OUT_REQUEST_ID = "out_request_id"
    const val OCN_MESSAGE_REQUEST_ID = "ocn_message_request_id"
    const val OCPI_FROM = "ocpi_from"
    const val OCPI_TO = "ocpi_to"
}

/** Attributes set on the active OpenTelemetry span (the agent's SERVER span while handling a request). */
object SpanKeys {
    const val CORRELATION_ID = "ocpi.correlation_id"
    const val REQUEST_ID = "ocpi.request_id"
    const val OUT_REQUEST_ID = "ocpi.out_request_id"
    const val FROM = "ocpi.from"
    const val TO = "ocpi.to"
    const val MODULE = "ocpi.module"
    const val INTERFACE_ROLE = "ocpi.interface_role"
    const val OCN_MESSAGE_REQUEST_ID = "ocn.message_request_id"
    const val FORWARD_EVENT = "ocpi.forward"
    const val URL_FULL = "url.full"
}

fun partyLabel(countryCode: String?, partyId: String?): String? =
        if (countryCode.isNullOrBlank() || partyId.isNullOrBlank()) null else "$countryCode/$partyId"

fun putTraceContext(requestId: String?, correlationId: String?, from: String?, to: String?) {
    putOrRemove(TraceKeys.REQUEST_ID, requestId)
    putOrRemove(TraceKeys.CORRELATION_ID, correlationId)
    putOrRemove(TraceKeys.OCPI_FROM, from)
    putOrRemove(TraceKeys.OCPI_TO, to)
    setSpanAttributes(
            SpanKeys.REQUEST_ID to requestId,
            SpanKeys.CORRELATION_ID to correlationId,
            SpanKeys.FROM to from,
            SpanKeys.TO to to
    )
}

fun putTraceContext(headers: OcnHeaders) =
        putTraceContext(
                requestId = headers.requestID,
                correlationId = headers.correlationID,
                from = headers.sender.label(),
                to = headers.receiver.label()
        )

private fun BasicRole.label() = partyLabel(country, id)

fun putOcnMessageRequestId(requestId: String) {
    MDC.put(TraceKeys.OCN_MESSAGE_REQUEST_ID, requestId)
    setSpanAttributes(SpanKeys.OCN_MESSAGE_REQUEST_ID to requestId)
}

fun tagOcpiRoute(module: String, interfaceRole: String) =
        setSpanAttributes(SpanKeys.MODULE to module, SpanKeys.INTERFACE_ROLE to interfaceRole)

/**
 * The agent's Ktor CLIENT span only exists inside `client.request`, so the outbound ID is recorded
 * as an event on the span that triggered the call instead.
 */
fun recordOutboundRequest(outRequestId: String?, url: String) {
    if (outRequestId.isNullOrBlank()) return
    Span.current().addEvent(
            SpanKeys.FORWARD_EVENT,
            Attributes.builder()
                    .put(SpanKeys.OUT_REQUEST_ID, outRequestId)
                    .put(SpanKeys.URL_FULL, url)
                    .build()
    )
}

private fun setSpanAttributes(vararg attributes: Pair<String, String?>) {
    val span = Span.current()
    attributes.forEach { (key, value) -> if (!value.isNullOrBlank()) span.setAttribute(key, value) }
}

private fun putOrRemove(key: String, value: String?) {
    if (value.isNullOrBlank()) MDC.remove(key) else MDC.put(key, value)
}

fun currentCorrelationId(): String? = MDC.get(TraceKeys.CORRELATION_ID)?.takeIf { it.isNotBlank() }

/** Runs [block] with [key] set in the MDC, restoring the previous value afterwards. */
inline fun <R> withMdc(key: String, value: String?, block: () -> R): R {
    if (value.isNullOrBlank()) return block()
    val previous = MDC.get(key)
    MDC.put(key, value)
    try {
        return block()
    } finally {
        if (previous == null) MDC.remove(key) else MDC.put(key, previous)
    }
}

/**
 * Reuses the correlation ID of the request being handled so calls this node makes on its behalf
 * stay linked to it; a new one is only created when the node itself originates the exchange.
 */
inline fun <R> withCorrelationId(block: (correlationId: String) -> R): R {
    val correlationId = currentCorrelationId() ?: generateUUIDv4Token()
    return withMdc(TraceKeys.CORRELATION_ID, correlationId) { block(correlationId) }
}

fun maskAuthorization(value: String): String {
    val scheme = value.substringBefore(' ', missingDelimiterValue = "")
    val credential = value.substringAfter(' ').trim()
    // Short credentials would be mostly revealed by their last four characters.
    val masked = "****" + if (credential.length > 8) credential.takeLast(4) else ""
    return if (scheme.isEmpty()) masked else "$scheme $masked"
}

fun Map<String, String?>.withMaskedAuthorization(): Map<String, String?> =
        mapValues { (name, value) ->
            if (value != null && name.equals(AUTHORIZATION, ignoreCase = true)) maskAuthorization(value)
            else value
        }
