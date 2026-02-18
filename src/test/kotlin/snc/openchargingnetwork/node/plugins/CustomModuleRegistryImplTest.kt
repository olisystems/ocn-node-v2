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
import snc.openchargingnetwork.node.plugins.core.CustomModuleHandler
import snc.openchargingnetwork.node.plugins.core.CustomModuleRegistryImpl
import snc.openchargingnetwork.node.plugins.core.CustomModuleRequest
import snc.openchargingnetwork.node.plugins.core.CustomModuleResponse
import org.springframework.http.HttpMethod

class CustomModuleRegistryImplTest {

    private lateinit var registry: CustomModuleRegistryImpl

    @org.junit.jupiter.api.BeforeEach
    fun setUp() {
        registry = CustomModuleRegistryImpl()
    }

    @Test
    fun `register and getHandler returns handler`() {
        val handler = CustomModuleHandler { CustomModuleResponse(1000, null, "data") }
        registry.register("p1", "mymodule", handler)

        assertThat(registry.getHandler("mymodule")).isSameAs(handler)
        assertThat(registry.getRegistration("mymodule")!!.pluginId).isEqualTo("p1")
        assertThat(registry.getRegistration("mymodule")!!.customModuleId).isEqualTo("mymodule")
    }

    @Test
    fun `getHandler is case insensitive`() {
        registry.register("p1", "MyModule", CustomModuleHandler { CustomModuleResponse(1000) })
        assertThat(registry.getHandler("mymodule")).isNotNull
        assertThat(registry.getHandler("MYMODULE")).isNotNull
    }

    @Test
    fun `first registration wins on collision`() {
        val first = CustomModuleHandler { CustomModuleResponse(1000, null, "first") }
        val second = CustomModuleHandler { CustomModuleResponse(1000, null, "second") }
        registry.register("p1", "mod", first)
        registry.register("p2", "mod", second)

        val got = registry.getHandler("mod")!!.handle(
            CustomModuleRequest("sender", "mod", null, HttpMethod.GET, emptyMap(), null, "A", "DE", "B", "DE", emptyMap())
        )
        assertThat(got.data).isEqualTo("first")
    }

    @Test
    fun `getHandler returns null for unregistered module`() {
        assertThat(registry.getHandler("none")).isNull()
        assertThat(registry.getRegistration("none")).isNull()
    }

    @Test
    fun `unregister removes plugin modules`() {
        registry.register("p1", "a", CustomModuleHandler { CustomModuleResponse(1000) })
        registry.register("p2", "b", CustomModuleHandler { CustomModuleResponse(1000) })

        registry.unregister("p1")

        assertThat(registry.getHandler("a")).isNull()
        assertThat(registry.getHandler("b")).isNotNull
        assertThat(registry.listModules()).hasSize(1)
    }

    @Test
    fun `listModules returns all registered`() {
        registry.register("p1", "m1", CustomModuleHandler { CustomModuleResponse(1000) })
        registry.register("p1", "m2", CustomModuleHandler { CustomModuleResponse(1000) })

        val list = registry.listModules()
        assertThat(list).hasSize(2)
        assertThat(list.map { it.customModuleId }).containsExactlyInAnyOrder("m1", "m2")
    }
}
