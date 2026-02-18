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
import snc.openchargingnetwork.node.config.PluginProperties
import snc.openchargingnetwork.node.plugins.core.NodePlugin
import snc.openchargingnetwork.node.plugins.core.PluginContext
import snc.openchargingnetwork.node.plugins.core.PluginDescriptor
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.ServiceLoader
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.springframework.stereotype.Component

data class LoadedPlugin(
    val descriptor: PluginDescriptor,
    val plugin: NodePlugin,
    val source: String
)

data class PluginLoadResult(
    val loaded: List<LoadedPlugin>,
    val failed: List<PluginLoadFailure>
)

data class PluginLoadFailure(
    val source: String,
    val reason: String,
    val cause: Throwable? = null
)

@Component
class PluginLoader(
    private val properties: PluginProperties,
    private val context: PluginContext
) {

    private val log = LoggerFactory.getLogger(PluginLoader::class.java)
    private val spiName = NodePlugin::class.java.name

    fun load(): PluginLoadResult {
        val dir = File(properties.dir)
        if (!dir.isDirectory) {
            log.info("Plugins directory does not exist or is not a directory: {}", dir.absolutePath)
            return PluginLoadResult(emptyList(), emptyList())
        }

        val jars = dir.listFiles { _, name -> name.lowercase().endsWith(".jar") }?.toList().orEmpty()
        if (jars.isEmpty()) {
            log.info("No plugin JARs found in {}", dir.absolutePath)
            return PluginLoadResult(emptyList(), emptyList())
        }

        val loaded = mutableListOf<LoadedPlugin>()
        val failed = mutableListOf<PluginLoadFailure>()
        val seenIds = mutableSetOf<String>()

        for (jar in jars.sortedBy { it.name }) {
            val source = jar.absolutePath
            try {
                val pluginsInJar = loadPluginsFromJar(jar, source)
                for (lp in pluginsInJar) {
                    if (!seenIds.add(lp.descriptor.id)) {
                        failed.add(PluginLoadFailure(source, "Duplicate plugin ID: ${lp.descriptor.id}"))
                        continue
                    }
                    loaded.add(lp)
                    log.info("Loaded plugin: {} v{} from {}", lp.descriptor.id, lp.descriptor.version, source)
                }
            } catch (e: Throwable) {
                log.warn("Failed to load plugin JAR {}: {}", source, e.message)
                failed.add(PluginLoadFailure(source, e.message ?: "Unknown error", e))
            }
        }

        // Init all loaded plugins with timeout
        val initFailures = mutableListOf<PluginLoadFailure>()
        val successfullyInited = mutableListOf<LoadedPlugin>()
        for (lp in loaded) {
            try {
                initPlugin(lp)
                successfullyInited.add(lp)
            } catch (e: Throwable) {
                log.warn("Plugin init failed for {}: {}", lp.descriptor.id, e.message)
                initFailures.add(PluginLoadFailure(lp.source, "init failed: ${e.message}", e))
            }
        }
        loaded.clear()
        loaded.addAll(successfullyInited)
        failed.addAll(initFailures)

        if (failed.isNotEmpty() && properties.failOnLoadError) {
            throw IllegalStateException(
                "Plugin load/init failures (ocn.plugins.failOnLoadError=true): " +
                    failed.joinToString("; ") { "${it.source}: ${it.reason}" }
            )
        }

        log.info("Plugin load complete: {} loaded, {} failed", loaded.size, failed.size)
        return PluginLoadResult(loaded, failed)
    }

    private fun loadPluginsFromJar(jar: File, source: String): List<LoadedPlugin> {
        val url = jar.toURI().toURL()
        val parent = Thread.currentThread().contextClassLoader
        val loader = URLClassLoader(arrayOf(url), parent)

        val serviceLoader = ServiceLoader.load(NodePlugin::class.java, loader)
        val result = mutableListOf<LoadedPlugin>()
        for (plugin in serviceLoader) {
            val descriptor = PluginDescriptor(
                id = plugin.id(),
                version = plugin.version(),
                displayName = null,
                source = source
            )
            result.add(LoadedPlugin(descriptor, plugin, source))
        }
        if (result.isEmpty()) {
            log.debug("JAR {} contains no {} implementations", source, spiName)
        }
        return result
    }

    private fun initPlugin(lp: LoadedPlugin) {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit(Callable {
                lp.plugin.init(context)
            })
            future.get(properties.initTimeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
