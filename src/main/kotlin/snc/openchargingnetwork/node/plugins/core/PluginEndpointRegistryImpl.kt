/*
    Copyright 2019-2020 eMobility GmbH

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package snc.openchargingnetwork.node.plugins.core

import org.springframework.http.HttpMethod
import org.springframework.http.server.PathContainer
import org.springframework.stereotype.Component
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import java.util.concurrent.CopyOnWriteArrayList

@Component
class PluginEndpointRegistryImpl : PluginEndpointRegistry {

    private val parser = PathPatternParser()
    private val routes = CopyOnWriteArrayList<Route>()

    override fun register(pluginId: String, path: String, method: HttpMethod, handler: PluginEndpointHandler) {
        val patternSource = normalizePath(path)
        routes.add(
            Route(
                pluginId = pluginId,
                pattern = parser.parse(patternSource),
                patternSource = patternSource,
                method = method,
                handler = handler
            )
        )
    }

    override fun unregister(pluginId: String) {
        routes.removeIf { it.pluginId == pluginId }
    }

    override fun resolve(path: String, method: HttpMethod): RegisteredEndpoint? {
        val normalized = normalizePath(path)
        val pathContainer = PathContainer.parsePath(normalized)
        val match = routes.asSequence()
            .filter { it.method == method && it.pattern.matches(pathContainer) }
            .maxByOrNull { it.patternSource.length }
            ?: return null

        return RegisteredEndpoint(
            pluginId = match.pluginId,
            path = normalized,
            method = method,
            handler = match.handler
        )
    }

    override fun listEndpoints(): List<RegisteredEndpoint> =
        routes.map { route ->
            RegisteredEndpoint(
                pluginId = route.pluginId,
                path = route.patternSource,
                method = route.method,
                handler = route.handler
            )
        }

    private fun normalizePath(path: String): String {
        var normalized = path.trim()
        if (!normalized.startsWith("/")) {
            normalized = "/$normalized"
        }
        if (normalized.length > 1 && normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        return normalized
    }

    private data class Route(
        val pluginId: String,
        val pattern: PathPattern,
        val patternSource: String,
        val method: HttpMethod,
        val handler: PluginEndpointHandler
    )
}
