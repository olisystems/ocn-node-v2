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

/** Routes object pushes addressed to the node identity. */
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
        if (!isNodeIdentity(receiver) || routingService.isRoleConnected(receiver)) {
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
        logger.info(
            "[NodeObjectRoute] No connected handler for {}/{}; broadcasting {} {}",
            receiver.country,
            receiver.id,
            request.method,
            request.module
        )
        moduleNotificationService.broadcastObjectRequestAsync(request)

        return ResponseEntity.ok(OcpiResponse<T>(statusCode = OcpiStatus.SUCCESS.code))
    }

    private fun isNodeIdentity(role: BasicRole): Boolean {
        return role.country.equals(nodeProperties.countryCode, ignoreCase = true) &&
            role.id.equals(nodeProperties.partyId, ignoreCase = true)
    }
}
