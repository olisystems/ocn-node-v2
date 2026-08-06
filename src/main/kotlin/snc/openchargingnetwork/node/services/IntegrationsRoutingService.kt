package snc.openchargingnetwork.node.services;

import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID

@Service
class IntegrationsRoutingService(
    private val registryService: RegistryService
) {
    private fun getIntegrationPartyForModule(module: ModuleID): BasicRole? {
        return when (module) {
            ModuleID.CDRS, ModuleID.TOKENS, ModuleID.SESSIONS -> BasicRole("OLI", "DE")
            ModuleID.LOCATIONS, ModuleID.TARIFFS -> BasicRole("BAN", "DE")
            else -> null
        }
    }

    fun getIntegrationReceivingParties(module: ModuleID, from: BasicRole): List<BasicRole> {
        val target = getIntegrationPartyForModule(module) ?: return emptyList()
        return listOf(target).filter { role -> role.id != from.id || role.country != from.country }
    }

    fun getSenderIntegrationReceivingParties(module: ModuleID, from: BasicRole): List<BasicRole> {
        val target = getIntegrationPartyForModule(module) ?: return emptyList()
        return listOf(target).filter { role -> role.id != from.id || role.country != from.country }
    }
}
