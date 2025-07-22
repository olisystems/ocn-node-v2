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

package snc.openchargingnetwork.node.config

import org.springframework.boot.context.properties.ConfigurationProperties
import snc.openchargingnetwork.node.tools.generateUUIDv4Token

@ConfigurationProperties("ocn.node")
class NodeProperties {

    // development mode
    var dev: Boolean = false

    // admin key used for remote management
    var apikey: String = generateUUIDv4Token()

    // i.e., ocn-node/v2.1
    var apiPrefix: String? = null

    // Ethereum account to sign messages and txs
    var privateKey: String? = null

    // Enable signature checking from communicating parties and nodes
    var signatures: Boolean = true

    // OCN Node public URL, used for health checking and broadcasting
    lateinit var url: String

    // If Enabled keeps a record of live and unresponsive parties
    var stillAliveEnabled: Boolean = true

    var stillAliveRate: Long = 900000

    var hubClientInfoSyncRate: Long = 3600000

    // If Enabled runs the enhanced hub client info sync task (includes pull + push operations)
    var hubClientInfoSyncEnabled: Boolean = false
}
