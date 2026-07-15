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
import snc.openchargingnetwork.node.tools.fromBs64String

@Component
class OcpiPlatformAuthService(private val platformRepository: PlatformRepository) {

    /**
     * Accept Token A/B/C. Tokens may arrive as plain or Base64 (OCPI common practice); match develop's
     * InternalVersionsController validation.
     *
     * Look up the raw token first so a plain token that happens to be valid Base64 is not rewritten
     * before matching. Only then try Base64 decoding; decode failures are treated as invalid auth.
     */
    fun assertTokenAOrC(authorization: String) {
        val rawToken = authorization.extractToken()
        if (matchesAnyAuthToken(rawToken)) {
            return
        }

        val decodedToken =
                try {
                    rawToken.fromBs64String()
                } catch (_: IllegalArgumentException) {
                    throw OcpiClientInvalidParametersException("Invalid authorization token")
                }

        if (decodedToken != rawToken && matchesAnyAuthToken(decodedToken)) {
            return
        }

        throw OcpiClientInvalidParametersException("Invalid authorization token")
    }

    private fun matchesAnyAuthToken(token: String): Boolean =
            platformRepository.existsByAuth_TokenA(token) ||
                    platformRepository.existsByAuth_TokenB(token) ||
                    platformRepository.existsByAuth_TokenC(token)
}
