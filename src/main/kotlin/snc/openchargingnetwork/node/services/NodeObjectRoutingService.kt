package snc.openchargingnetwork.node.services

import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.components.OcpiRequestHandlerBuilder
import snc.openchargingnetwork.node.config.NodeProperties
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
 *    c. Async-broadcast to all parties with the module RECEIVER enabled.
 *    d. Return OCPI success to the caller (broadcast continues in the background).
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
            if (sendingNodeSignature == null) {
                handler.forwardDefault()
            } else {
                handler.forwardFromOcn(sendingNodeSignature)
            }
        }

        // Fan-out to every connected party that has this module as RECEIVER.
        logger.info(
            "[NodeObjectRoute] Broadcasting {} {} addressed to hub {}/{}",
            request.method,
            request.module,
            receiver.country,
            receiver.id
        )
        moduleNotificationService.broadcastObjectRequestAsync(request)

        // Caller gets success once routing/broadcast is accepted; recipients are notified async.
        return ResponseEntity.ok(OcpiResponse<T>(statusCode = OcpiStatus.SUCCESS.code))
    }

    /** Whether [role] is this OCN node's configured hub identity. */
    private fun isNodeIdentity(role: BasicRole): Boolean {
        return role.country.equals(nodeProperties.countryCode, ignoreCase = true) &&
            role.id.equals(nodeProperties.partyId, ignoreCase = true)
    }
}
