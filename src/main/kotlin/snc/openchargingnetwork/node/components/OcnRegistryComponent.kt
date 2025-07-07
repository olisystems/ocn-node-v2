package snc.openchargingnetwork.node.components

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.context.annotation.ApplicationScope
import org.springframework.web.server.ResponseStatusException
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.OcnRegistry

@ApplicationScope
@Component
class OcnRegistryComponent(
    private val httpClientComponent: HttpClientComponent,
    private val registryIndexerProperties: RegistryIndexerProperties
) {
    private var registry = OcnRegistry()

    fun getRegistry(forceReload: Boolean=false): OcnRegistry {
        if (!forceReload and registry.parties.isNotEmpty() and registry.operators.isNotEmpty()) {
            return registry
        }
        val response = httpClientComponent.getIndexedOcnRegistry(
            url = registryIndexerProperties.url,
            authorization = registryIndexerProperties.token,
            query = registryIndexerProperties.aggregatedQuery
        )
        if (!response.success) {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }
        registry = OcnRegistry(
            parties = response.data?.parties!!,
            operators = response.data.operators!!
        )
        return registry
    }
}