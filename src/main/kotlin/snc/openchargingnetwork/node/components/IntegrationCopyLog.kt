package snc.openchargingnetwork.node.components

import org.slf4j.Logger
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables

/**
 * Logs the outcome of an integration copy: the extra copy of a token, session or CDR request that
 * the node sends to a fixed integration party (see IntegrationsRoutingService). A copy is not
 * part of OCPI error propagation, so its result never reaches the sender and these lines are the
 * only place a failed copy shows up. Every failure contains "integration copy" and "FAILED" so
 * one log query or alert finds all of them.
 */
internal fun Logger.logIntegrationCopy(
        tag: String,
        request: OcpiRequestVariables,
        target: BasicRole,
        outRequestId: String?,
        response: OcpiHttpResponse<*>
) {
    if (response.isOcpiSuccess()) {
        info(
                "[{}] SUCCESS — integration copy of {} {} to {} | HTTP {} | ocpi status: {} | out_request_id: {}",
                tag, request.method, request.objectPath(), target.label(), response.statusCode,
                response.body?.statusCode, outRequestId
        )
    } else {
        error(
                "[{}] FAILED — integration copy of {} {} to {} was rejected | HTTP {} | ocpi status: {} | message: {} | out_request_id: {} | not reported to sender {}",
                tag, request.method, request.objectPath(), target.label(), response.statusCode,
                response.body?.statusCode, response.body?.statusMessage?.forLogLine(), outRequestId,
                request.headers.sender.label()
        )
    }
}

internal fun Logger.logIntegrationCopyError(
        tag: String,
        request: OcpiRequestVariables,
        target: BasicRole,
        outRequestId: String?,
        cause: Exception
) {
    error(
            "[{}] FAILED — integration copy of {} {} to {} was not delivered | error: {}: {} | out_request_id: {} | not reported to sender {}",
            tag, request.method, request.objectPath(), target.label(), cause::class.simpleName, cause.message?.forLogLine(),
            outRequestId, request.headers.sender.label()
    )
}

/** Longest receiver-supplied text (status or exception message) these lines include. */
private const val MAX_LOGGED_TEXT = 500

/**
 * The receiver controls its status message, and an exception message can quote its answer, so
 * both are kept to one line: control characters and Unicode line separators are escaped (a raw
 * newline would start a line that looks like a separate log entry), and long text is cut.
 */
private fun String.forLogLine(): String {
    val text = if (length > MAX_LOGGED_TEXT) "${take(MAX_LOGGED_TEXT)}... [truncated]" else this
    return buildString(text.length) {
        for (c in text) {
            when {
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c.isISOControl() || c == '\u2028' || c == '\u2029' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}

private fun OcpiRequestVariables.objectPath(): String =
        if (urlPath.isNullOrBlank()) resolveModuleId() else "${resolveModuleId()}/${urlPath.removePrefix("/")}"

private fun BasicRole.label() = "$country/$id"
