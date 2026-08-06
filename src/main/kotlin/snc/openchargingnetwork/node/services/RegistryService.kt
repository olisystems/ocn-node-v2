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
import snc.openchargingnetwork.node.models.exceptions.OcpiHubUnknownReceiverException
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
     * Helper to extract the main domain (e.g., oli-system.com) from a URL.
     */
    private fun extractMainDomain(url: String): String {
        // Remove protocol
        val domain = url.replace(Regex("^https?://"), "")
            .split("/")[0]
            .split(":")[0]
        // Extract last two parts (e.g., oli-system.com)
        val parts = domain.split(".")
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else domain
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
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, role)

        if (belongsToMe) {
            val myKey = Credentials.create(properties.privateKey).address
            val opMainDomain = extractMainDomain(op.domain)
            val myMainDomain = extractMainDomain(properties.url)
            var domainMatches = opMainDomain == myMainDomain
            var idMatches = Keys.toChecksumAddress(op.id) == Keys.toChecksumAddress(myKey)

            if (properties.dev) {
                logger.info("[Registry] {}/{} belongs to this node (dev mode - domain/id checks bypassed)", role.country, role.id)
                domainMatches = true
                idMatches = true
            } else {
                logger.info(
                    "[Registry] {}/{} belongs-to-me check: domain={} (match={}) address={} (match={})",
                    role.country, role.id, opMainDomain, domainMatches, op.id, idMatches
                )
            }
            return domainMatches && idMatches
        }

        val isKnown = op.domain.isNotEmpty()
        logger.info("[Registry] {}/{} is a remote role — OCN node domain: {} (known={})", role.country, role.id, op.domain, isKnown)
        return isKnown
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
        val party = registry.parties.firstOrNull { it.partyId == role.id && it.countryCode == role.country }
            ?: throw OcpiHubUnknownReceiverException("Role ${role.country}-${role.id} not registered on OCN Registry")
        return RegistryPartyDetailsBasic(address = party.partyAddress, operator = party.operator.id)
    }

}
