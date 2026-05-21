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

import com.fasterxml.jackson.core.type.TypeReference
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

    fun adaptOutboundRequest(request: OcpiRequestVariables): OcpiRequestVariables {
        val platformId = routingService.getPlatformID(request.headers.receiver)
        val adapter = protocolAdapters.firstOrNull { it.supportsPlatform(platformId) } ?: return request
        return adapter.transformOutboundRequest(platformId, request)
    }

    fun <T : Any> adaptInboundResponse(
            request: OcpiRequestVariables,
            response: OcpiHttpResponse<T>
    ): OcpiHttpResponse<T> {
        val platformId = routingService.getPlatformID(request.headers.receiver)
        val adapter = protocolAdapters.firstOrNull { it.supportsPlatform(platformId) } ?: return response
        val body = response.body ?: return response
        val json = httpClientComponent.mapper.writeValueAsString(body)
        val transformed =
                adapter.transformInboundResponse(
                        platformId,
                        request.module,
                        request.headers.receiver,
                        json
                )
        val parsed: OcpiResponse<T> =
                httpClientComponent.mapper.readValue(
                        transformed,
                        object : TypeReference<OcpiResponse<T>>() {}
                )
        return response.copy(body = parsed)
    }
}
