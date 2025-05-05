package snc.openchargingnetwork.node.tools

import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.exceptions.OcpiHubUnknownReceiverException
import snc.openchargingnetwork.node.models.ocpi.BasicRole

/**
 * Support function
 */
fun filterOperatorsByParty(registry: OcnRegistry, role: BasicRole): Operator {
    val operators = registry.operators.filter {
        it.parties.any {
            it.partyId == role.id && it.countryCode == role.country } }
    if (operators.isEmpty()) throw OcpiHubUnknownReceiverException("Recipient not registered on OCN")
    return operators.first()
}
