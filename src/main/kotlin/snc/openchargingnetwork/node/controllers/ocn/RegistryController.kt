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

package snc.openchargingnetwork.node.controllers.ocn

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.web3j.crypto.Credentials
import snc.openchargingnetwork.node.config.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.RegistryIndexerProperties
import snc.openchargingnetwork.node.models.*
import snc.openchargingnetwork.node.tools.toBs64String


@RestController
@RequestMapping("\${ocn.node.apiPrefix}/ocn/registry")
class RegistryController(
    private val properties: NodeProperties,
    private val registryIndexerProperties: RegistryIndexerProperties,
    private val httpClientComponent: HttpClientComponent,
) {

    fun isAuthorized(authorization: String): Boolean {
        return authorization == "Token ${properties.apikey}" ||
                authorization == "Token ${properties.apikey.toBs64String()}"
    }

    @GetMapping("/node-info")
    fun getMyNodeInfo() = mapOf(
        "url" to properties.url + "/" + properties.apiPrefix,
        "address" to Credentials.create(properties.privateKey).address
    )

    @GetMapping("/nodes")
    fun getRegisteredNodes(): List<Party>? {
        val response: ControllerResponse<GqlData> = httpClientComponent.getIndexedOcnRegistryParties(
            registryIndexerProperties.url,
            registryIndexerProperties.token,
            registryIndexerProperties.partiesQuery
        )
        if (response.success) {
            return response.data!!.parties!!
        } else {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }
    }

    @GetMapping("/node/{countryCode}/{partyID}")
    fun getNodeOf(
        @PathVariable countryCode: String,
        @PathVariable partyID: String
    ): Party? {
        val partyID = "${countryCode}/${partyID}"
        val response: ControllerResponse<GqlData> = httpClientComponent.getIndexedOcnRegistryParties(
            registryIndexerProperties.url,
            registryIndexerProperties.token,
            registryIndexerProperties.singlePartyQuery.format(partyID)
        )
        if (response.success) {
            return response.data!!.party!!
        } else {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }
    }

    @GetMapping("/node/{countryCode}/{partyID}/certificates")
    fun getNodeCertificatesOf(
        @RequestHeader("Authorization") authorization: String,
        @PathVariable countryCode: String,
        @PathVariable partyID: String
    ): GqlCertificateDataResponse? {
        if (!isAuthorized(authorization)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        }

        val partyID = "${countryCode} ${partyID}"
        val response: ControllerResponse<GqlCertificateData> = httpClientComponent.getIndexedOcnRegistryCertificates(
            registryIndexerProperties.url,
            registryIndexerProperties.token,
            registryIndexerProperties.singleVerificationQuery.format(partyID,partyID,partyID)
        )
        if (response.success) {
            val prettyResponse = GqlCertificateDataResponse(response.data!!.emp,response.data!!.cpo,response.data!!.other,)
            return prettyResponse
        } else {
            throw ResponseStatusException(HttpStatus.METHOD_FAILURE, response.error)
        }
    }

}