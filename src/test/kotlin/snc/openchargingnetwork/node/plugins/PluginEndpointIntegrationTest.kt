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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRegistryImpl
import snc.openchargingnetwork.node.plugins.core.PluginEndpointHandler
import snc.openchargingnetwork.node.plugins.core.PluginEndpointResponse

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PluginEndpointIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var endpointRegistry: PluginEndpointRegistryImpl

    @Test
    fun `plugin endpoint returns 404 when no handler registered`() {
        mockMvc.perform(get("/ocn-v2/plugin/hello"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `plugin endpoint returns handler response when registered`() {
        val path = "/test-integration-${System.currentTimeMillis()}"
        endpointRegistry.register(
            "test",
            path,
            HttpMethod.GET,
            PluginEndpointHandler { PluginEndpointResponse(200, """{"message":"Hello from plugin"}""", "application/json") }
        )
        try {
            val result = mockMvc.perform(get("/ocn-v2/plugin${path}"))
                .andExpect(status().isOk)
                .andReturn()

            assertThat(result.response.contentType).isNotNull
            assertThat(result.response.contentAsString).contains("Hello from plugin")
        } finally {
            endpointRegistry.unregister("test")
        }
    }
}
