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
import snc.openchargingnetwork.node.controllers.ocpi.v2_2.extractCustomModuleUrlPath
import snc.openchargingnetwork.node.plugins.core.CustomModule
import snc.openchargingnetwork.node.plugins.core.CustomModuleRequest
import snc.openchargingnetwork.node.plugins.core.CustomModuleResponse

class CustomModuleDispatchTest {

    @Test
    fun `modules are resolved case-insensitively`() {
        val modules =
                listOf(
                        object : CustomModule {
                            override fun moduleId() = "MyModule"

                            override fun handle(request: CustomModuleRequest) =
                                    CustomModuleResponse(1000, null, "ok")
                        }
                )
        val byId = buildMap { modules.forEach { putIfAbsent(it.moduleId().lowercase(), it) } }

        assertThat(byId["mymodule"]).isNotNull
        assertThat(byId["MyModule".lowercase()]).isNotNull
    }

    @Test
    fun `first module wins when duplicate ids`() {
        val first =
                object : CustomModule {
                    override fun moduleId() = "mod"

                    override fun handle(request: CustomModuleRequest) =
                            CustomModuleResponse(1000, null, "first")
                }
        val second =
                object : CustomModule {
                    override fun moduleId() = "mod"

                    override fun handle(request: CustomModuleRequest) =
                            CustomModuleResponse(1000, null, "second")
                }
        val byId = buildMap {
            listOf(first, second).forEach { putIfAbsent(it.moduleId().lowercase(), it) }
        }

        val response =
                byId["mod"]!!.handle(
                        CustomModuleRequest(
                                "sender",
                                "mod",
                                null,
                                HttpMethod.GET,
                                emptyMap(),
                                null,
                                "A",
                                "DE",
                                "B",
                                "DE",
                                emptyMap()
                        )
                )
        assertThat(response.data).isEqualTo("first")
        assertThat(response.statusCode).isEqualTo(1000)
    }

    @Test
    fun `extractCustomModuleUrlPath returns relative path without scheme or prefix`() {
        assertThat(
                        extractCustomModuleUrlPath(
                                "/ocn-v2/ocpi/custom/sender/example/extra/path",
                                "sender",
                                "example"
                        )
                )
                .isEqualTo("/extra/path")

        assertThat(
                        extractCustomModuleUrlPath(
                                "/ocn-v2/ocpi/custom/receiver/mymodule",
                                "receiver",
                                "mymodule"
                        )
                )
                .isNull()

        // Full URI strings are not expected (controller uses URI.path), but marker search still
        // yields a clean relative suffix rather than leaving scheme/host attached.
        assertThat(
                        extractCustomModuleUrlPath(
                                "http://localhost:8080/ocn-v2/ocpi/custom/sender/example/x",
                                "sender",
                                "example"
                        )
                )
                .isEqualTo("/x")
    }
}
