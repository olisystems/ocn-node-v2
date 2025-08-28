package snc.openchargingnetwork.node.services

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.components.OcpiRequestHandler
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.RegistryNode
import snc.openchargingnetwork.node.models.RegistryPartyDetailsBasic
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.tools.checksum
import snc.openchargingnetwork.node.tools.filterOperatorByParty

/**
 * Simplifies calls to the Registry (registry and permissions smart contracts)
 */
@Service
class RegistryService(
    private val ocnRegistryComponent: OcnRegistryComponent,
    private val properties: NodeProperties,

    ) {

    companion object {
        private var logger: Logger = LoggerFactory.getLogger(OcpiRequestHandler::class.java)
    }

    /**
     * Get nodes listed in registry
     */
    fun getNodes(omitMine: Boolean = false): List<RegistryNode> {
        val registry = ocnRegistryComponent.getRegistry()
        val nodesList: MutableList<RegistryNode> = mutableListOf()
        for (operator in registry.operators) {
            val node = RegistryNode(
                operator = operator.id, operator.domain)
            nodesList.add(node)
        }
        if (omitMine) {
            val myAddress = Credentials.create(properties.privateKey).address.checksum()
            return nodesList.filter { it.operator != myAddress }
        }
        return nodesList
    }

    /**
     * check OCN registry to see if basic role is registered
     */
    fun isRoleKnown(role: BasicRole, belongsToMe: Boolean = true): Boolean {
        logger.info("### BEGIN isRoleKnown verifications ###")
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, role)

        logger.info("belongsToMe: {}", belongsToMe)

        if (belongsToMe) {
            val myKey = Credentials.create(properties.privateKey).address
            var domainMatches = op.domain == properties.url
            var idMatches = Keys.toChecksumAddress(op.id) == Keys.toChecksumAddress(myKey)

            logger.info("Verifying if domain matches | op.domain == properties.url | {} == {}", op.domain, properties.url)
            logger.info("Domain matches: {}", domainMatches)
            logger.info("Verifying if Address id matches | op.id == myKey | {} == {}", op.id, myKey)
            logger.info("Address id matches: {}", idMatches)

            // bypass checks in dev mode, used for local development
            if (properties.dev){
                logger.info("Dev mode enabled - bypassing Domain and ID match checks")
                domainMatches  = true
                idMatches = true
            }
            logger.info("### END isRoleKnown verifications ###")
            return domainMatches && idMatches
        }

        val result = op.domain != ""
        logger.info("belongsToMe is false - checking if op.domain is not empty: {} -> result: {}", op.domain, result)
        logger.info("### END isRoleKnown verifications ###")
        return result
    }

    /**
     * get the OCN Node URL as registered by the basic role in the OCN Registry
     */
    fun getRemoteNodeUrlOf(role: BasicRole): String {
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, role)
        return op.domain
    }

    /**
     * Returns basic party details (address and node operator)
     */
    fun getPartyDetails(role: BasicRole): RegistryPartyDetailsBasic {
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, role)
        val party = op.parties.first { it.partyId == role.id && it.countryCode == role.country }
        return RegistryPartyDetailsBasic(address = party.partyAddress, operator = party.operator.id)
    }

}
