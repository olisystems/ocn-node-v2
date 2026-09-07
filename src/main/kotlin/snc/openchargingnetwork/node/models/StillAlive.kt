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

package snc.openchargingnetwork.node.models

import com.fasterxml.jackson.annotation.JsonProperty
import snc.openchargingnetwork.node.models.ocpi.ConnectionStatus

/** Outcome of a still alive check covering several platforms. */
data class StillAliveCheckSummary(
        @JsonProperty("checked") val checked: Int,
        @JsonProperty("connected") val connected: Int,
        @JsonProperty("offline") val offline: Int,
        @JsonProperty("skipped") val skipped: Int,
        @JsonProperty("message") val message: String
)

/** Outcome of a still alive check for the platform a single party is registered on. */
data class PartyStillAliveResult(
        @JsonProperty("country_code") val countryCode: String,
        @JsonProperty("party_id") val partyID: String,
        @JsonProperty("status") val status: ConnectionStatus,
        @JsonProperty("affected_parties") val affectedParties: Int,
        @JsonProperty("message") val message: String
)
