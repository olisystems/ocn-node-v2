package snc.openchargingnetwork.node.services

import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.BroadcastDelivery
import snc.openchargingnetwork.node.models.BroadcastDelivery.Outcome
import snc.openchargingnetwork.node.models.exceptions.OcpiBroadcastDeliveryException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiStatus

/**
 * Primary router for Locations / Tariffs / Tokens RECEIVER object pushes (PUT/PATCH/DELETE).
 *
 * Replaces the old controller pattern of only:
 *   build().forwardDefault().getResponse()
 * for those mutations, because hub-addressed traffic must fan out, not go to a single peer.
 *
 * Decision tree for [route]:
 * 1. OCPI-to is a normal party → behave like before (forwardDefault / forwardFromOcn).
 * 2. OCPI-to is this node's hub identity (e.g. DE/BAN):
 *    a. Validate the inbound request for broadcast.
 *    b. If a same-identity hub backend is connected and the sender is another party,
 *       forward once to that backend so it can persist the object.
 *    c. Broadcast to all parties with the module RECEIVER enabled, in parallel, waiting up to
 *       ocn.node.broadcastWaitMillis for their answers.
 *    d. Return OCPI success only if the backend and every receiver accepted it; otherwise an
 *       error naming each receiver that rejected it or has not answered yet.
 */
@Service
class NodeObjectRoutingService(
    private val requestHandlerBuilder: OcpiRequestHandlerBuilder,
    private val moduleNotificationService: ModuleNotificationService,
    private val routingService: RoutingService,
    private val nodeProperties: NodeProperties
) {

    companion object {
        private val logger = LoggerFactory.getLogger(NodeObjectRoutingService::class.java)
    }

    /** True for the mutation verbs/modules that may need hub broadcast instead of single forward. */
    fun handles(request: OcpiRequestVariables): Boolean {
        return request.interfaceRole == InterfaceRole.RECEIVER &&
            request.module in setOf(ModuleID.LOCATIONS, ModuleID.TARIFFS, ModuleID.TOKENS) &&
            request.method in setOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
    }

    /**
     * @param sendingNodeSignature when set, request arrived via OCN inter-node message path;
     * otherwise it is a direct OCPI client call to this node.
     */
    fun <T : Any> route(
        request: OcpiRequestVariables,
        sendingNodeSignature: String? = null
    ): ResponseEntity<OcpiResponse<T>> {
        val receiver = request.headers.receiver

        // Path A — not hub-addressed: keep legacy single-receiver semantics.
        // Controllers call this for Tokens/Locations/Tariffs mutations even when OCPI-to is a CPO/MSP,
        // so non-hub traffic still works like build().forwardDefault().getResponse().
        if (!isNodeIdentity(receiver)) {
            val handler = requestHandlerBuilder.build<T>(request)
            return if (sendingNodeSignature == null) {
                handler.forwardDefault().getResponse()
            } else {
                handler.forwardFromOcn(sendingNodeSignature).getResponseWithAllHeaders()
            }
        }

        // Path B — hub-addressed (OCPI-to matches this node's configured country/party).
        val handler = requestHandlerBuilder.build<T>(request)
        if (sendingNodeSignature == null) {
            handler.validateIncomingForBroadcast()
        } else {
            handler.validateIncomingFromOcnForBroadcast(sendingNodeSignature)
        }

        val deliveries = mutableListOf<BroadcastDelivery>()

        // Optional persist step: if a platform is registered as the hub identity itself
        // (same country/party as the node), forward the object there before broadcast so
        // NSP/TM/etc. can store it. Skip when the sender already is the hub identity.
        if (routingService.isRoleConnected(receiver) && !isNodeIdentity(request.headers.sender)) {
            logger.info(
                "[NodeObjectRoute] Forwarding {} {} to connected hub handler {}/{} before broadcast",
                request.method,
                request.module,
                receiver.country,
                receiver.id
            )
            val response =
                if (sendingNodeSignature == null) {
                    handler.forwardDefault().getResponse()
                } else {
                    handler.forwardFromOcn(sendingNodeSignature).getResponse()
                }
            deliveries += hubHandlerDelivery(receiver, response)
        }

        // Fan-out to every connected party that has this module as RECEIVER.
        logger.info(
            "[NodeObjectRoute] Broadcasting {} {} addressed to hub {}/{}",
            request.method,
            request.module,
            receiver.country,
            receiver.id
        )
        deliveries += moduleNotificationService.broadcastObjectRequest(request)

        return broadcastResponse(request, deliveries)
    }

    private fun hubHandlerDelivery(receiver: BasicRole, response: ResponseEntity<out OcpiResponse<*>>): BroadcastDelivery {
        val body = response.body
        val outcome =
            if (response.statusCode.is2xxSuccessful && body?.statusCode in 1000..1999) Outcome.DELIVERED
            else Outcome.REJECTED
        return BroadcastDelivery(receiver, outcome, response.statusCode.value(), body?.statusCode, body?.statusMessage)
    }

    /**
     * Success only when every receiver accepted the object. A single receiver's rejection keeps
     * its own status; with several receivers any failure is a 3000 naming each one, and receivers
     * that have not answered yet make it a 4002 (timeout on forwarded request).
     */
    private fun <T : Any> broadcastResponse(
        request: OcpiRequestVariables,
        deliveries: List<BroadcastDelivery>
    ): ResponseEntity<OcpiResponse<T>> {
        val unfinished = deliveries.filter { it.outcome != Outcome.DELIVERED }
        if (unfinished.isEmpty()) {
            return ResponseEntity.ok(OcpiResponse<T>(statusCode = OcpiStatus.SUCCESS.code))
        }

        val statusCode =
            when {
                deliveries.size == 1 -> {
                    val only = deliveries.single()
                    when (only.outcome) {
                        Outcome.REJECTED -> only.ocpiStatus?.takeIf { it >= 2000 } ?: OcpiStatus.SERVER_ERROR.code
                        Outcome.UNREACHABLE -> OcpiStatus.HUB_CONNECTION_PROBLEM.code
                        else -> OcpiStatus.HUB_REQUEST_TIMEOUT.code
                    }
                }
                unfinished.any { it.failed } -> OcpiStatus.SERVER_ERROR.code
                else -> OcpiStatus.HUB_REQUEST_TIMEOUT.code
            }
        val delivered = deliveries.size - unfinished.size
        val message =
            "Broadcast of ${request.method} ${request.module.id} reached $delivered of ${deliveries.size} receivers: " +
                unfinished.joinToString("; ") { it.describe() }
        logger.error("[NodeObjectRoute] {}", message)
        throw OcpiBroadcastDeliveryException(message, statusCode)
    }

    /** Whether [role] is this OCN node's configured hub identity. */
    private fun isNodeIdentity(role: BasicRole): Boolean {
        return role.country.equals(nodeProperties.countryCode, ignoreCase = true) &&
            role.id.equals(nodeProperties.partyId, ignoreCase = true)
    }
}
