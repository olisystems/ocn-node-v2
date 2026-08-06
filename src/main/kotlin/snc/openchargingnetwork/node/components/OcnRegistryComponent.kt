package snc.openchargingnetwork.node.components

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.ApplicationScope
import org.springframework.web.server.ResponseStatusException
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.ocpi.Role

@ApplicationScope
@Component
open class OcnRegistryComponent(
        private val httpClientComponent: HttpClientComponent,
        private val registryIndexerProperties: RegistryIndexerProperties,
        private val nodeProperties: NodeProperties
) {
    private var registry = OcnRegistry()

    open fun getRegistry(forceReload: Boolean = false): OcnRegistry {
        if (!forceReload && registry.parties.isNotEmpty() && registry.operators.isNotEmpty()) {
            return registry
        }
        val response =
                httpClientComponent.getIndexedOcnRegistry(
                        url = registryIndexerProperties.url,
                        authorization = registryIndexerProperties.token,
                        query = registryIndexerProperties.aggregatedQuery
                )
        if (!response.success) {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }
        registry =
                OcnRegistry(
                        parties = response.data?.parties!!,
                        operators = response.data.operators!!
                )
        return registry
    }

    private fun myPublicAddress(): String {
        val publicAddress = nodeProperties.publicAddress
        if (publicAddress.isNullOrBlank()) {
            throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Missing ocn.node.publicAddress"
            )
        }
        return publicAddress
    }

    fun findMyPartiesList(): List<Party> {
        val myAddress = myPublicAddress()
        val registry = getRegistry()
        return registry.parties.filter { it.operator.id.equals(myAddress, ignoreCase = true) }
    }

    fun findMyEmspPartiesList(): List<Party> {
        val myAddress = myPublicAddress()
        val registry = getRegistry()
        return registry.parties.filter {
            it.operator.id.equals(myAddress, ignoreCase = true) && it.roles.contains(Role.EMSP)
        }
    }

    fun findAllPartiesList(): List<Party> {
        val registry = getRegistry()
        return registry.parties
    }
}
