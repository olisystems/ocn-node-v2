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
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class PluginEndpointRegistryImpl : PluginEndpointRegistry {

    private val byKey = ConcurrentHashMap<String, RegisteredEndpoint>()
    private val byPlugin = ConcurrentHashMap<String, MutableSet<String>>()

    override fun register(pluginId: String, path: String, method: HttpMethod, handler: PluginEndpointHandler) {
        val normalized = normalizePath(path)
        val key = key(normalized, method)
        byKey[key] = RegisteredEndpoint(pluginId, normalized, method, handler)
        byPlugin.getOrPut(pluginId) { mutableSetOf() }.add(key)
    }

    override fun unregister(pluginId: String) {
        byPlugin.remove(pluginId)?.forEach { byKey.remove(it) }
    }

    override fun resolve(path: String, method: HttpMethod): RegisteredEndpoint? =
        byKey[key(normalizePath(path), method)]

    override fun listEndpoints(): List<RegisteredEndpoint> = byKey.values.toList()

    private fun key(path: String, method: HttpMethod) = "${method.toString()}:$path"

    private fun normalizePath(path: String): String {
        var p = path
        if (!p.startsWith("/")) p = "/$p"
        if (p.endsWith("/") && p.length > 1) p = p.dropLast(1)
        return p
    }
}
