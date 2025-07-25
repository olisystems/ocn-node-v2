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

import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.exceptions.InvalidOcnSignatureException
import snc.openchargingnetwork.node.models.exceptions.OcpiHubConnectionProblemException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ClientInfo
import snc.openchargingnetwork.node.tools.filterOperatorByParty
import java.nio.charset.StandardCharsets

/**
 * Provides methods for the node's wallet; sign and verify messages sent between nodes
 */
@Service
class WalletService(
    private val properties: NodeProperties,
    private val ocnRegistryComponent: OcnRegistryComponent,
    private val httpClientComponent: HttpClientComponent
) {

    /**
     * Take a component of a signature (r,s,v) and convert it to a string to include as an OCN-Signature header
     * in network requests
     */
    private fun toHexStringNoPrefix(bytes: ByteArray): String {
        return Numeric.cleanHexPrefix(Numeric.toHexString(bytes))
    }


    /**
     * Reverse an OCN-Signature string to get the original r,s,v values as byte arrays
     */
    fun signatureStringToByteArray(signature: String): Triple<ByteArray, ByteArray, ByteArray> {
        val cleanSignature = Numeric.cleanHexPrefix(signature)
        val r = Numeric.hexStringToByteArray(cleanSignature.substring(0, 64))
        val s = Numeric.hexStringToByteArray(cleanSignature.substring(64, 128))
        val v = Numeric.hexStringToByteArray(cleanSignature.substring(128, 130))
        return Triple(r, s, v)
    }


    /**
     * Sign an arbitrary string (used to sign the JSON body of a message sent over the network)
     */
    fun sign(request: String): String {
        val dataToSign = request.toByteArray(StandardCharsets.UTF_8)
        val credentials = Credentials.create(properties.privateKey)
        val signature = Sign.signPrefixedMessage(dataToSign, credentials.ecKeyPair)
        val r = toHexStringNoPrefix(signature.r)
        val s = toHexStringNoPrefix(signature.s)
        val v = toHexStringNoPrefix(signature.v)
        return r + s + v
    }


    /**
     * Verify that a request (as JSON string) was signed by the sender using the provided OCN-Signature
     */
    fun verify(request: String, signature: String, sender: BasicRole) {
        val dataToVerify = request.toByteArray(StandardCharsets.UTF_8)
        val (r, s, v) = signatureStringToByteArray(signature)
        val signingKey = Sign.signedPrefixedMessageToKey(dataToVerify, Sign.SignatureData(v, r, s))
        val signingAddress = "0x${Keys.getAddress(signingKey)}"
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, sender)
        if (signingAddress.lowercase() != op.id) {
            throw OcpiHubConnectionProblemException("Could not verify OCN-Signature of request")
        }
    }

    /**
     * Verify that a ClientInfo update belongs to the correct node of the party
     */
    fun verifyClientInfo(clientInfoString: String, signature: String): ClientInfo {
        // Fetch Operator from the Registry
        val clientInfo: ClientInfo = httpClientComponent.mapper.readValue(clientInfoString)
        val role = BasicRole(clientInfo.partyID, clientInfo.countryCode)
        val registry = ocnRegistryComponent.getRegistry()
        val op = filterOperatorByParty(registry, role)
        // Verify if signature matches address
        val dataToVerify = clientInfoString.toByteArray(StandardCharsets.UTF_8)
        val (r, s, v) = signatureStringToByteArray(signature)
        val signingKey = Sign.signedPrefixedMessageToKey(dataToVerify, Sign.SignatureData(v, r, s))
        val signingAddress = "0x${Keys.getAddress(signingKey)}"
        if (signingAddress.lowercase() != op.id) {
            throw InvalidOcnSignatureException(
                "Invalid OCN-Signature header. " +
                        "Client registered with operator $op but update signed by $signingAddress."
            )
        }
        return clientInfo
    }

}