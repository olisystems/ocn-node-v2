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

import kotlin.jvm.JvmOverloads
import org.springframework.http.HttpMethod

// Registry for plugin-provided HTTP routes under {apiPrefix}/plugin/...
// Prefer @RestController for new plugins; this remains for route tables built at startup.
interface PluginEndpointRegistry {

    fun register(
        pluginId: String,
        path: String,
        method: HttpMethod,
        handler: PluginEndpointHandler
    )

    fun unregister(pluginId: String)

    fun resolve(path: String, method: HttpMethod): RegisteredEndpoint?

    fun listEndpoints(): List<RegisteredEndpoint>
}

data class RegisteredEndpoint(
    val pluginId: String,
    val path: String,
    val method: HttpMethod,
    val handler: PluginEndpointHandler
)

fun interface PluginEndpointHandler {

    fun handle(request: PluginEndpointRequest): PluginEndpointResponse
}

data class PluginEndpointRequest(
    val path: String,
    val method: HttpMethod,
    val queryParams: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val body: String?
)

data class PluginEndpointResponse @JvmOverloads constructor(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String? = "application/json",
    val headers: Map<String, String> = emptyMap()
)
