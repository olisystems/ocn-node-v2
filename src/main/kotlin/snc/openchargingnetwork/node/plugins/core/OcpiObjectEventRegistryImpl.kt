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

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class OcpiObjectEventRegistryImpl : OcpiObjectEventRegistry {

    private val log = LoggerFactory.getLogger(OcpiObjectEventRegistryImpl::class.java)
    private val handlers = ConcurrentHashMap<String, RegisteredOcpiObjectEventHandler>()

    override fun register(pluginId: String, handler: OcpiObjectEventHandler) {
        handlers[pluginId] = RegisteredOcpiObjectEventHandler(pluginId, handler)
    }

    override fun unregister(pluginId: String) {
        handlers.remove(pluginId)
    }

    override fun publish(event: OcpiObjectEvent) {
        handlers.values.forEach { registered ->
            try {
                registered.handler.handle(event)
            } catch (e: Exception) {
                log.warn(
                    "OCPI object event handler failed for plugin {}, module {}, phase {}: {}",
                    registered.pluginId,
                    event.module,
                    event.phase,
                    e.message,
                    e
                )
            }
        }
    }

    override fun listHandlers(): List<RegisteredOcpiObjectEventHandler> = handlers.values.toList()
}
