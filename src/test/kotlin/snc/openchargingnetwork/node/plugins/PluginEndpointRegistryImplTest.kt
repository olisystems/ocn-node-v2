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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.plugins.core.PluginEndpointHandler
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRegistryImpl
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRequest
import snc.openchargingnetwork.node.plugins.core.PluginEndpointResponse

class PluginEndpointRegistryImplTest {

    private lateinit var registry: PluginEndpointRegistryImpl

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        registry = PluginEndpointRegistryImpl()
    }

    @Test
    fun `register and resolve returns handler`() {
        val handler = PluginEndpointHandler { PluginEndpointResponse(200, "ok") }
        registry.register("p1", "/foo", HttpMethod.GET, handler)

        val resolved = registry.resolve("/foo", HttpMethod.GET)
        assertThat(resolved).isNotNull
        assertThat(resolved!!.pluginId).isEqualTo("p1")
        assertThat(resolved.path).isEqualTo("/foo")
        assertThat(resolved.method).isEqualTo(HttpMethod.GET)
        assertThat(resolved.handler.handle(PluginEndpointRequest("/foo", HttpMethod.GET, emptyMap(), emptyMap(), null)).statusCode).isEqualTo(200)
    }

    @Test
    fun `normalizes path with leading slash`() {
        registry.register("p1", "bar", HttpMethod.GET, PluginEndpointHandler { PluginEndpointResponse(200) })
        assertThat(registry.resolve("/bar", HttpMethod.GET)).isNotNull
    }

    @Test
    fun `resolve returns null for unregistered path`() {
        assertThat(registry.resolve("/missing", HttpMethod.GET)).isNull()
        assertThat(registry.resolve("/foo", HttpMethod.POST)).isNull()
    }

    @Test
    fun `listEndpoints returns all registered`() {
        registry.register("p1", "/a", HttpMethod.GET, PluginEndpointHandler { PluginEndpointResponse(200) })
        registry.register("p1", "/b", HttpMethod.POST, PluginEndpointHandler { PluginEndpointResponse(201) })

        val list = registry.listEndpoints()
        assertThat(list).hasSize(2)
        assertThat(list.map { it.path to it.method }).containsExactlyInAnyOrder("/a" to HttpMethod.GET, "/b" to HttpMethod.POST)
    }

    @Test
    fun `unregister removes plugin endpoints`() {
        registry.register("p1", "/a", HttpMethod.GET, PluginEndpointHandler { PluginEndpointResponse(200) })
        registry.register("p2", "/b", HttpMethod.GET, PluginEndpointHandler { PluginEndpointResponse(200) })

        registry.unregister("p1")

        assertThat(registry.resolve("/a", HttpMethod.GET)).isNull()
        assertThat(registry.resolve("/b", HttpMethod.GET)).isNotNull
        assertThat(registry.listEndpoints()).hasSize(1)
    }

    @Test
    fun `same path different method are separate entries`() {
        registry.register("p1", "/foo", HttpMethod.GET, PluginEndpointHandler { PluginEndpointResponse(200) })
        registry.register("p1", "/foo", HttpMethod.POST, PluginEndpointHandler { PluginEndpointResponse(201) })

        assertThat(registry.resolve("/foo", HttpMethod.GET)!!.handler.handle(PluginEndpointRequest("", HttpMethod.GET, emptyMap(), emptyMap(), null)).statusCode).isEqualTo(200)
        assertThat(registry.resolve("/foo", HttpMethod.POST)!!.handler.handle(PluginEndpointRequest("", HttpMethod.POST, emptyMap(), emptyMap(), null)).statusCode).isEqualTo(201)
    }
}
