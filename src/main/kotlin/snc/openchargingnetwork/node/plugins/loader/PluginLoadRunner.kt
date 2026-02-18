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

package snc.openchargingnetwork.node.plugins.loader

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Int.MIN_VALUE + 100)
class PluginLoadRunner(private val pluginLoader: PluginLoader) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(PluginLoadRunner::class.java)

    override fun run(args: ApplicationArguments?) {
        val result = pluginLoader.load()
        if (result.loaded.isEmpty() && result.failed.isEmpty()) return
        log.info(
            "Plugins: {} loaded ({}), {} failed",
            result.loaded.size,
            result.loaded.joinToString(", ") { it.descriptor.id },
            result.failed.size
        )
        result.failed.forEach { f ->
            log.warn("Plugin failure: {} - {}", f.source, f.reason, f.cause)
        }
    }
}
