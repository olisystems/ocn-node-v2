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

package snc.openchargingnetwork.node.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.support.GenericApplicationContext
import snc.openchargingnetwork.node.config.PluginProperties
import snc.openchargingnetwork.node.plugins.core.CustomModuleRegistryImpl
import snc.openchargingnetwork.node.plugins.core.PluginContext
import snc.openchargingnetwork.node.plugins.core.PluginContextImpl
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRegistryImpl
import snc.openchargingnetwork.node.plugins.loader.PluginLoader
import java.io.File

class PluginLoaderTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `load returns empty when directory does not exist`() {
        val props = PluginProperties().apply { dir = tempDir.resolve("nonexistent").absolutePath }
        val context = pluginContext()
        val loader = PluginLoader(props, context)

        val result = loader.load()

        assertTrue(result.loaded.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `load returns empty when directory is empty`() {
        val props = PluginProperties().apply { dir = tempDir.absolutePath }
        val context = pluginContext()
        val loader = PluginLoader(props, context)

        val result = loader.load()

        assertTrue(result.loaded.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `load returns empty when directory has no jar files`() {
        tempDir.resolve("readme.txt").writeText("not a jar")
        val props = PluginProperties().apply { dir = tempDir.absolutePath }
        val context = pluginContext()
        val loader = PluginLoader(props, context)

        val result = loader.load()

        assertTrue(result.loaded.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `load with failOnLoadError false does not throw when jar is invalid`() {
        tempDir.resolve("bad.jar").writeBytes(byteArrayOf(1, 2, 3))
        val props = PluginProperties().apply {
            dir = tempDir.absolutePath
            failOnLoadError = false
        }
        val context = pluginContext()
        val loader = PluginLoader(props, context)

        val result = loader.load()

        assertTrue(result.loaded.isEmpty(), "invalid JAR should not load any plugin")
        assertTrue(!result.failed.isNotEmpty() || result.failed[0].source.endsWith("bad.jar"),
            "if there are failures, first should be for bad.jar")
    }

    private fun pluginContext(): PluginContext {
        val ctx = GenericApplicationContext()
        ctx.refresh()
        val endpointRegistry = PluginEndpointRegistryImpl()
        val customModuleRegistry = CustomModuleRegistryImpl()
        return PluginContextImpl(ctx, endpointRegistry, customModuleRegistry)
    }
}
