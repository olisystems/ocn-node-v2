package snc.openchargingnetwork.node.services

import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.RegistryNode
import snc.openchargingnetwork.node.models.RegistryPartyDetailsBasic
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.tools.checksum
import snc.openchargingnetwork.node.tools.filterOperatorsByParty

/**
 * Simplifies calls to the Registry (registry and permissions smart contracts)
 * TODO: review tests
 */
@Service
class RegistryService(private val registry: OcnRegistry,
                      private val properties: NodeProperties) {

    /**
     * Get nodes listed in registry
     */
    fun getNodes(omitMine: Boolean = false): List<RegistryNode> {
        val nodes = registry.operators.map { RegistryNode(operator = it.id, url = it.domain) }
        if (omitMine) {
            val myAddress = Credentials.create(properties.privateKey).address.checksum()
            return nodes.filter { it.operator != myAddress }
        }
        return nodes
    }

    /**
     * check OCN registry to see if basic role is registered
     */
    fun isRoleKnown(role: BasicRole, belongsToMe: Boolean = true): Boolean {
        val op = filterOperatorsByParty(registry, role)
        if (belongsToMe) {
            val myKey = Credentials.create(properties.privateKey).address
            val domainMatches = op.domain == properties.url
            val idMatches = Keys.toChecksumAddress(op.id) == Keys.toChecksumAddress(myKey)
            return domainMatches && idMatches
        }
        return op.domain == properties.url
    }

    /**
     * get the OCN Node URL as registered by the basic role in the OCN Registry
     */
    fun getRemoteNodeUrlOf(role: BasicRole): String {
        val op = filterOperatorsByParty(registry, role)
        return op.domain
    }

    /**
     * Returns basic party details (address and node operator)
     */
    fun getPartyDetails(role: BasicRole): RegistryPartyDetailsBasic {
        val op = filterOperatorsByParty(registry, role)
        val party = op.parties.first { it.partyId == role.id && it.countryCode == role.country }
        return RegistryPartyDetailsBasic(address = party.partyAddress, operator = party.operator.id)
    }


}