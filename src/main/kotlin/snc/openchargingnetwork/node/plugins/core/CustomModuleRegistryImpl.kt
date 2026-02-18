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

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class CustomModuleRegistryImpl : CustomModuleRegistry {

    private val byModuleId = ConcurrentHashMap<String, RegisteredCustomModule>()
    private val byPlugin = ConcurrentHashMap<String, MutableSet<String>>()

    override fun register(pluginId: String, customModuleId: String, handler: CustomModuleHandler) {
        val id = customModuleId.lowercase()
        if (!byModuleId.containsKey(id)) {
            byModuleId[id] = RegisteredCustomModule(pluginId, id, handler)
            byPlugin.getOrPut(pluginId) { mutableSetOf() }.add(id)
        }
    }

    override fun getHandler(customModuleId: String): CustomModuleHandler? =
        byModuleId[customModuleId.lowercase()]?.handler

    override fun unregister(pluginId: String) {
        byPlugin.remove(pluginId)?.forEach { byModuleId.remove(it) }
    }

    override fun getRegistration(customModuleId: String): RegisteredCustomModule? =
        byModuleId[customModuleId.lowercase()]

    override fun listModules(): List<RegisteredCustomModule> = byModuleId.values.toList()
}
