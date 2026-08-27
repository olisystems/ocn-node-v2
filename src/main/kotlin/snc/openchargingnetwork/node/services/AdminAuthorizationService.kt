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

package snc.openchargingnetwork.node.services

import org.springframework.stereotype.Service
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.tools.toBs64String

/**
 * Single source of truth for the admin API key check shared by every admin controller. The node's
 * API key is accepted either verbatim or base64-encoded.
 */
@Service
class AdminAuthorizationService(private val properties: NodeProperties) {

    fun isAuthorized(authorization: String): Boolean {
        return authorization == "Token ${properties.apikey}" ||
                authorization == "Token ${properties.apikey.toBs64String()}"
    }
}
