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

import org.springframework.http.HttpMethod
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID

/** Published when standard OCPI objects pass through the node (request body or response data). */
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

enum class OcpiObjectEventPhase {
    REQUEST_BODY,
    RESPONSE_DATA
}
