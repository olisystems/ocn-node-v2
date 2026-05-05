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

import org.springframework.context.ApplicationContext
import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID

/**
 * Trusted plugin API: exposes Spring context and extension registries so plugins
 * can register custom endpoints and custom OCPI modules.
 */
interface PluginContext {

    /** Spring ApplicationContext for full trusted access (beans, repositories, etc.). */
    fun applicationContext(): ApplicationContext

    /** Registry for custom non-OCPI HTTP endpoints (e.g. under /plugins/...). */
    fun endpointRegistry(): PluginEndpointRegistry

    /** Registry for custom OCPI module handlers (custom module ID -> handler). */
    fun customModuleRegistry(): CustomModuleRegistry

    /** Registry for standard OCPI object events moving through the node. */
    fun ocpiObjectEventRegistry(): OcpiObjectEventRegistry
}

/**
 * Registry for plugin-provided HTTP endpoint handlers (path + method -> handler).
 */
interface PluginEndpointRegistry {

    /**
     * Register an endpoint. Path should be relative to the plugin base (e.g. "/my-resource").
     * Collisions with existing registrations will be reported at startup.
     */
    fun register(
        pluginId: String,
        path: String,
        method: HttpMethod,
        handler: PluginEndpointHandler
    )

    fun unregister(pluginId: String)

    /** Resolve handler for path and method (path normalized, no leading/trailing slash). */
    fun resolve(path: String, method: HttpMethod): RegisteredEndpoint?

    /** All registered endpoints for dispatch and collision checks. */
    fun listEndpoints(): List<RegisteredEndpoint>
}

data class RegisteredEndpoint(
    val pluginId: String,
    val path: String,
    val method: HttpMethod,
    val handler: PluginEndpointHandler
)

/**
 * Handler for a plugin-registered HTTP endpoint. Receives raw path, query, body and returns response.
 */
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

data class PluginEndpointResponse(
    val statusCode: Int,
    val body: String? = null,
    val contentType: String? = "application/json"
)

/**
 * Registry for custom OCPI module handlers. Key is the custom module ID (e.g. "mymodule").
 */
interface CustomModuleRegistry {

    /**
     * Register a handler for a custom module ID. First registration wins on collision.
     */
    fun register(pluginId: String, customModuleId: String, handler: CustomModuleHandler)

    fun getHandler(customModuleId: String): CustomModuleHandler?

    fun unregister(pluginId: String)

    /** Registration details for a custom module. */
    fun getRegistration(customModuleId: String): RegisteredCustomModule?

    fun listModules(): List<RegisteredCustomModule>
}

data class RegisteredCustomModule(
    val pluginId: String,
    val customModuleId: String,
    val handler: CustomModuleHandler
)

/**
 * Handler for custom OCPI module requests (sender/receiver, path, method, body).
 */
fun interface CustomModuleHandler {

    fun handle(request: CustomModuleRequest): CustomModuleResponse
}

data class CustomModuleRequest(
    val interfaceRole: String,
    val customModuleId: String,
    val urlPath: String?,
    val method: org.springframework.http.HttpMethod,
    val queryParams: Map<String, Any?>,
    val body: String?,
    val fromPartyId: String,
    val fromCountryCode: String,
    val toPartyId: String,
    val toCountryCode: String,
    val headers: Map<String, String>
)

data class CustomModuleResponse(
    val statusCode: Int,
    val statusMessage: String? = null,
    val data: Any? = null,
    val body: String? = null
)

interface OcpiObjectEventRegistry {

    fun register(pluginId: String, handler: OcpiObjectEventHandler)

    fun unregister(pluginId: String)

    fun publish(event: OcpiObjectEvent)

    fun listHandlers(): List<RegisteredOcpiObjectEventHandler>
}

data class RegisteredOcpiObjectEventHandler(
    val pluginId: String,
    val handler: OcpiObjectEventHandler
)

/**
 * Handler for standard OCPI objects observed while they pass through the node.
 */
fun interface OcpiObjectEventHandler {

    fun handle(event: OcpiObjectEvent)
}

enum class OcpiObjectEventPhase {
    REQUEST_BODY,
    RESPONSE_DATA
}

data class OcpiObjectEvent(
    val phase: OcpiObjectEventPhase,
    val module: ModuleID,
    val interfaceRole: InterfaceRole,
    val method: HttpMethod,
    val urlPath: String?,
    val customModuleId: String?,
    val queryParams: Map<String, Any?>,
    val payload: Any,
    val payloadIndex: Int?,
    val fromPartyId: String,
    val fromCountryCode: String,
    val toPartyId: String,
    val toCountryCode: String,
    val headers: Map<String, String>,
    val responseStatusCode: Int?,
    val ocpiStatusCode: Int?
)
