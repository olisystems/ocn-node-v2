package snc.openchargingnetwork.node.services;

import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.config.IntegrationsProperties
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID

@Service
class IntegrationsRoutingService(
    private val registryService: RegistryService,
    properties: IntegrationsProperties
) {
    // Parsed at startup so a malformed target stops the node instead of failing every copy.
    private val partiesByModule: Map<ModuleID, List<BasicRole>> =
        with(properties.targets) {
            mapOf(
                ModuleID.CDRS to cdrs,
                ModuleID.TOKENS to tokens,
                ModuleID.SESSIONS to sessions,
                ModuleID.LOCATIONS to locations,
                ModuleID.TARIFFS to tariffs
            ).mapValues { (_, targets) -> targets.filter { it.isNotBlank() }.map(::parseParty) }
        }

    private fun parseParty(target: String): BasicRole {
        val parts = target.trim().split("/")
        require(parts.size == 2) { "Integration target \"$target\" is not written as COUNTRY/PARTY" }
        return BasicRole(id = parts[1], country = parts[0]).uppercase()
    }

    private fun partiesExcept(module: ModuleID, from: BasicRole): List<BasicRole> =
        partiesByModule[module].orEmpty().filter { role -> role.id != from.id || role.country != from.country }

    fun getIntegrationReceivingParties(module: ModuleID, from: BasicRole): List<BasicRole> =
        partiesExcept(module, from)

    fun getSenderIntegrationReceivingParties(module: ModuleID, from: BasicRole): List<BasicRole> =
        partiesExcept(module, from)
}
