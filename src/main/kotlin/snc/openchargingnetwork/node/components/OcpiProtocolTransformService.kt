/*
    Copyright 2026 OLI Systems GmbH

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

package snc.openchargingnetwork.node.components

import com.fasterxml.jackson.databind.JavaType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.plugins.core.OcpiProtocolAdapter
import snc.openchargingnetwork.node.services.RoutingService

@Component
class OcpiProtocolTransformService(
        private val routingService: RoutingService,
        private val httpClientComponent: HttpClientComponent,
        private val protocolAdapters: List<OcpiProtocolAdapter>
) {

    private val log = LoggerFactory.getLogger(OcpiProtocolTransformService::class.java)

    fun adaptOutboundRequest(request: OcpiRequestVariables): OcpiRequestVariables {
        val platformId = routingService.getPlatformID(request.headers.receiver)
        val adapter = protocolAdapters.firstOrNull { it.supportsPlatform(platformId) } ?: return request
        return try {
            adapter.transformOutboundRequest(platformId, request)
        } catch (e: Exception) {
            log.warn(
                    "Outbound protocol adapt failed for platform {}: {} — using original request",
                    platformId,
                    e.message
            )
            request
        }
    }

    fun <T : Any> adaptInboundResponse(
            request: OcpiRequestVariables,
            response: OcpiHttpResponse<T>
    ): OcpiHttpResponse<T> {
        val platformId = routingService.getPlatformID(request.headers.receiver)
        val adapter = protocolAdapters.firstOrNull { it.supportsPlatform(platformId) } ?: return response
        val body = response.body ?: return response
        return try {
            val json = httpClientComponent.mapper.writeValueAsString(body)
            val transformed =
                    adapter.transformInboundResponse(
                            platformId,
                            request.module,
                            request.headers.receiver,
                            json
                    )
            val responseType = ocpiResponseJavaType(body)
            @Suppress("UNCHECKED_CAST")
            val parsed: OcpiResponse<T> =
                    httpClientComponent.mapper.readValue(transformed, responseType) as OcpiResponse<T>
            response.copy(body = parsed)
        } catch (e: Exception) {
            log.warn(
                    "Inbound protocol adapt failed for platform {} module {}: {} — returning original response",
                    platformId,
                    request.module,
                    e.message
            )
            response
        }
    }

    /**
     * Reconstruct OcpiResponse&lt;T&gt; using the runtime type of [body].data when present, so adapted
     * JSON keeps a usable payload type instead of raw maps from erased TypeReference&lt;T&gt;.
     */
    private fun <T : Any> ocpiResponseJavaType(body: OcpiResponse<T>): JavaType {
        val typeFactory = httpClientComponent.mapper.typeFactory
        val dataType: JavaType =
                when (val data = body.data) {
                    null -> typeFactory.constructType(Any::class.java)
                    is Collection<*> -> {
                        val elementClass =
                                data.firstOrNull()?.javaClass ?: Any::class.java
                        @Suppress("UNCHECKED_CAST")
                        typeFactory.constructCollectionType(
                                data.javaClass as Class<out MutableCollection<*>>,
                                elementClass
                        )
                    }
                    is Array<*> -> {
                        val elementClass =
                                data.javaClass.componentType ?: Any::class.java
                        typeFactory.constructArrayType(elementClass)
                    }
                    else -> typeFactory.constructType(data.javaClass)
                }
        return typeFactory.constructParametricType(OcpiResponse::class.java, dataType)
    }
}
