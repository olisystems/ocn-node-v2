package snc.openchargingnetwork.node.components

    import org.springframework.http.HttpStatus
    import org.springframework.stereotype.Component
    import org.springframework.web.context.annotation.ApplicationScope
    import org.springframework.web.server.ResponseStatusException
    import org.web3j.crypto.Credentials
    import snc.openchargingnetwork.node.config.RegistryIndexerProperties
    import snc.openchargingnetwork.node.config.NodeProperties
    import snc.openchargingnetwork.node.models.OcnRegistry
    import snc.openchargingnetwork.node.models.Party

    @ApplicationScope
    @Component
    class OcnRegistryComponent(
        private val httpClientComponent: HttpClientComponent,
        private val registryIndexerProperties: RegistryIndexerProperties,
        private val nodeProperties: NodeProperties
    ) {
        private var registry = OcnRegistry()

        fun getRegistry(forceReload: Boolean = false): OcnRegistry {
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

        private fun myPublicAddress(): String {
            val pk = nodeProperties.privateKey
                ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Missing ocn.node.privateKey")
            // Returns EIP‑55 checksum address, prefixed with 0x
            return Credentials.create(pk).address
        }

        fun findMyPartiesList(): List<Party> {
            val myAddress = myPublicAddress()
            val registry = getRegistry()
            return registry.parties.filter { it.operator.id.equals(myAddress, ignoreCase = true) }
        }

        fun findAllPartiesList(): List<Party> {
            val registry = getRegistry()
            return registry.parties
        }
    }