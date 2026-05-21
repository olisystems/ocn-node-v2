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

import org.springframework.stereotype.Component
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.repositories.PlatformRepository
import snc.openchargingnetwork.node.tools.extractToken

@Component
class OcpiPlatformAuthService(private val platformRepository: PlatformRepository) {

    fun assertTokenAOrC(authorization: String) {
        val token = authorization.extractToken()
        val valid =
                platformRepository.existsByAuth_TokenA(token) ||
                        platformRepository.existsByAuth_TokenC(token)
        if (!valid) {
            throw OcpiClientInvalidParametersException("Invalid CREDENTIALS_TOKEN_A")
        }
    }
}
