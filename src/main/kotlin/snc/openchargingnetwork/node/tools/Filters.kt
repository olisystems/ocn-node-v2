package snc.openchargingnetwork.node.tools

import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.exceptions.OcpiHubUnknownReceiverException
import snc.openchargingnetwork.node.models.ocpi.BasicRole

/**
 * Support function
 */
fun filterOperatorByParty(registry: OcnRegistry, role: BasicRole): Operator {
    val parties = registry.parties.filter {
            it.partyId == role.id && it.countryCode == role.country
    }
    if (parties.isEmpty()) throw OcpiHubUnknownReceiverException("Recipient not registered on OCN")
    return parties.first().operator
}
