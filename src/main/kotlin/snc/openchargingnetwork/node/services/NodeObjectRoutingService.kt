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
 * Routes object pushes addressed to the hub identity (e.g. DE/BAN).
 *
 * Messages to the hub are broadcast to parties with the module RECEIVER enabled. When a same-identity
 * handler is connected, inbound pushes from other parties are also forwarded there for storage.
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

    fun handles(request: OcpiRequestVariables): Boolean {
        return request.interfaceRole == InterfaceRole.RECEIVER &&
            request.module in setOf(ModuleID.LOCATIONS, ModuleID.TARIFFS, ModuleID.TOKENS) &&
            request.method in setOf(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
    }

    fun <T : Any> route(
        request: OcpiRequestVariables,
        sendingNodeSignature: String? = null
    ): ResponseEntity<OcpiResponse<T>> {
        val receiver = request.headers.receiver

        // Not addressed to the hub → normal single-receiver routing
        if (!isNodeIdentity(receiver)) {
            val handler = requestHandlerBuilder.build<T>(request)
            return if (sendingNodeSignature == null) {
                handler.forwardDefault().getResponse()
            } else {
                handler.forwardFromOcn(sendingNodeSignature).getResponseWithAllHeaders()
            }
        }

        val handler = requestHandlerBuilder.build<T>(request)
        if (sendingNodeSignature == null) {
            handler.validateIncomingForBroadcast()
        } else {
            handler.validateIncomingFromOcnForBroadcast(sendingNodeSignature)
        }

        // Inbound from another party while a hub backend is connected → store there first
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

        logger.info(
            "[NodeObjectRoute] Broadcasting {} {} addressed to hub {}/{}",
            request.method,
            request.module,
            receiver.country,
            receiver.id
        )
        moduleNotificationService.broadcastObjectRequestAsync(request)

        return ResponseEntity.ok(OcpiResponse<T>(statusCode = OcpiStatus.SUCCESS.code))
    }

    private fun isNodeIdentity(role: BasicRole): Boolean {
        return role.country.equals(nodeProperties.countryCode, ignoreCase = true) &&
            role.id.equals(nodeProperties.partyId, ignoreCase = true)
    }
}
