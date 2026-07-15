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

package snc.openchargingnetwork.node.models.ocpi

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RegistrationRoleRequest(
        @JsonProperty("country_code") val country: String,
        @JsonProperty("party_id") val id: String,
        @JsonProperty("credentials_role") val credentialsRole: Role? = null,
        @JsonProperty("interface_role") val interfaceRole: InterfaceRole? = null
) {
    fun toBasicRole(): BasicRole = BasicRole(id = id, country = country)
}
