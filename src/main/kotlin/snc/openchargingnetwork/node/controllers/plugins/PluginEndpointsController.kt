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

package snc.openchargingnetwork.node.controllers.plugins

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRegistry
import snc.openchargingnetwork.node.plugins.core.PluginEndpointRequest
import java.util.Collections

@RequestMapping("\${ocn.node.apiPrefix}/plugins")
@RestController
class PluginEndpointsController(private val endpointRegistry: PluginEndpointRegistry) {

    @RequestMapping(
        value = ["", "/", "/**"],
        method = [
            org.springframework.web.bind.annotation.RequestMethod.GET,
            org.springframework.web.bind.annotation.RequestMethod.POST,
            org.springframework.web.bind.annotation.RequestMethod.PUT,
            org.springframework.web.bind.annotation.RequestMethod.PATCH,
            org.springframework.web.bind.annotation.RequestMethod.DELETE,
            org.springframework.web.bind.annotation.RequestMethod.HEAD,
            org.springframework.web.bind.annotation.RequestMethod.OPTIONS
        ]
    )
    fun dispatch(
        request: HttpServletRequest,
        @RequestBody(required = false) body: String?
    ): ResponseEntity<String> {
        val path = pathAfterPrefix(request)
        val method = HttpMethod.valueOf(request.method)
        val registered = endpointRegistry.resolve(path, method)
            ?: return ResponseEntity.notFound().build()

        val queryParams = request.parameterNames.asSequence().associateWith { name ->
            request.getParameterValues(name)?.toList() ?: emptyList()
        }
        val headers = request.headerNames.asSequence().associateWith { name ->
            Collections.list(request.getHeaders(name))
        }

        val pluginRequest = PluginEndpointRequest(
            path = path,
            method = method,
            queryParams = queryParams,
            headers = headers,
            body = body
        )
        val response = registered.handler.handle(pluginRequest)

        val contentType = response.contentType?.let { MediaType.parseMediaType(it) } ?: MediaType.APPLICATION_JSON
        return ResponseEntity
            .status(response.statusCode)
            .contentType(contentType)
            .body(response.body)
    }

    private fun pathAfterPrefix(request: HttpServletRequest): String {
        val uri = request.requestURI ?: ""
        val pluginsIndex = uri.indexOf("/plugins")
        if (pluginsIndex < 0) return "/"
        val after = uri.substring(pluginsIndex + "/plugins".length).trimStart('/').trimEnd('/')
        return if (after.isEmpty()) "/" else "/$after"
    }
}
