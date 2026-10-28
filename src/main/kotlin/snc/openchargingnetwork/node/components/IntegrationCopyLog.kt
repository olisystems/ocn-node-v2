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
                response.body?.statusCode, response.body?.statusMessage, outRequestId,
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
            tag, request.method, request.objectPath(), target.label(), cause::class.simpleName, cause.message,
            outRequestId, request.headers.sender.label()
    )
}

private fun OcpiRequestVariables.objectPath(): String =
        if (urlPath.isNullOrBlank()) resolveModuleId() else "${resolveModuleId()}/${urlPath.removePrefix("/")}"

private fun BasicRole.label() = "$country/$id"
