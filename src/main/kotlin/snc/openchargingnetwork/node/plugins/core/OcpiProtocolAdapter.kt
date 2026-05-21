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

package snc.openchargingnetwork.node.plugins.core

import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables

/**
 * Plugin SPI: translate OCPI request/response bodies for platforms using a non-native protocol
 * version (e.g. 2.1.1).
 */
interface OcpiProtocolAdapter {

    val protocolVersion: String

    fun supportsPlatform(platformId: Long): Boolean

    fun transformOutboundRequest(
            platformId: Long,
            request: OcpiRequestVariables
    ): OcpiRequestVariables

    fun transformInboundResponse(
            platformId: Long,
            module: ModuleID,
            receiver: BasicRole,
            responseJson: String
    ): String
}
