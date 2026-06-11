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

package snc.openchargingnetwork.node.components

import org.slf4j.LoggerFactory
import org.web3j.crypto.Keys
import shareandcharge.openchargingnetwork.notary.Notary
import shareandcharge.openchargingnetwork.notary.ValuesToSign
import snc.openchargingnetwork.node.config.HaasProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.SignatureVerificationStatus
import snc.openchargingnetwork.node.services.RegistryService
import snc.openchargingnetwork.node.services.RoutingService

open class OcpiMessageHandler(
    val request: OcpiRequestVariables,
    val properties: NodeProperties,
    val haasProperties: HaasProperties,
    val routingService: RoutingService,
    val registryService: RegistryService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(OcpiMessageHandler::class.java)
    }

    /**
     * Notary object instantiated after validating a request.
     * Only if message signing property (signatures) set to true OR request contains "OCN-Signature" header.
     */
    var notary: Notary? = null

    /**
     * Result of signature verification (request-level). Set by validateOcnSignature() and surfaced
     * in the response body as ocn_verification_status when signing is not enforced.
     */
    var verificationStatus: SignatureVerificationStatus? = null

    /**
     * Check message signing is enabled. Can be enabled in the following ways:
     * 1. ocn.node.signatures property is set to true
     * 2. request contains a signature header
     * 3. (optional) recipient requires it (overrides other settings)
     *
     */
    fun isSigningActive(recipient: BasicRole? = null): Boolean {
        // Only activate signing if the property is true
        if (!properties.signatures) return false
        var hasSignatureHeader = request.headers.signature != null
        if(hasSignatureHeader) {
           if (recipient != null) {
               val recipientRules = routingService.getPlatformRules(recipient)
               hasSignatureHeader = recipientRules.signatures
           }
           return hasSignatureHeader
        }
        return false
    }

    /**
     * Use the OCN Notary to validate a request's "OCN-Signature" header.
     * When signing is active: throws on invalid/missing signature and sets verificationStatus = VERIFIED on success.
     * When signing is disabled: always evaluates signature without throwing; sets verificationStatus to
     * VERIFIED, VERIFICATION_FAILED, or NOT_PRESENTED and logs a warning if invalid.
     *
     * @param signature the OCN signature contained in the request header or response body
     * @param signedValues the values signed by the sender
     * @param signer expected signatory of the signature
     * @param receiver optional receiver of message (checks their OcnRules for signature verification requirement)
     */
    fun validateOcnSignature(
        signature: String?,
        signedValues: ValuesToSign<*>,
        signer: BasicRole,
        receiver: BasicRole? = null
    ) {
        val enforcing = isSigningActive(receiver)

        fun setStatusFromVerification(): Boolean {
            if (signature == null) {
                verificationStatus = SignatureVerificationStatus.NOT_PRESENTED
                return false
            }
            return try {
                notary = Notary.deserialize(signature)
                val result = notary?.verify(signedValues) ?: run {
                    verificationStatus = SignatureVerificationStatus.VERIFICATION_FAILED
                    return false
                }
                if (!result.isValid) {
                    if (properties.signatures) {
                        logger.warn("Signature verification failed for {}: {}", signer, result.error)
                    }
                    verificationStatus = SignatureVerificationStatus.VERIFICATION_FAILED
                    return false
                }
                val party = registryService.getPartyDetails(signer)
                val actualSignatory = Keys.toChecksumAddress(notary?.signatory)
                val signedByParty = actualSignatory == Keys.toChecksumAddress(party.address)
                val signedByOperator = actualSignatory == Keys.toChecksumAddress(party.operator)
                if (signedByParty || signedByOperator) {
                    verificationStatus = SignatureVerificationStatus.VERIFIED
                    true
                } else {
                    if (properties.signatures) {
                        logger.warn(
                            "Signature signatory {} does not match party {} or operator {}",
                            actualSignatory, party.address, party.operator
                        )
                    }
                    verificationStatus = SignatureVerificationStatus.VERIFICATION_FAILED
                    false
                }
            } catch (e: Exception) {
                if (properties.signatures) {
                    logger.warn("Signature verification encountered an error for {}: {}", signer, e.message)
                }
                verificationStatus = SignatureVerificationStatus.VERIFICATION_FAILED
                false
            }
        }

        if (enforcing) {
            val result =
                try {
                    signature?.let {
                        notary = Notary.deserialize(it)
                        notary?.verify(signedValues)
                    }
                } catch (e: Exception) {
                    throw OcpiClientInvalidParametersException("Invalid signature: ${e.message}")
                }
            when {
                result == null -> throw OcpiClientInvalidParametersException("Missing OCN Signature")
                !result.isValid -> throw OcpiClientInvalidParametersException("Invalid signature: ${result.error}")
            }
            val party = registryService.getPartyDetails(signer)
            val actualSignatory = Keys.toChecksumAddress(notary?.signatory)
            val signedByParty = actualSignatory == Keys.toChecksumAddress(party.address)
            val signedByOperator = actualSignatory == Keys.toChecksumAddress(party.operator)
            if (!signedByParty && !signedByOperator) {
                throw OcpiClientInvalidParametersException("Actual signatory ${notary?.signatory} differs from expected signatory ${party.address} (party) or ${party.operator} (operator)")
            }
            verificationStatus = SignatureVerificationStatus.VERIFIED
        } else {
            setStatusFromVerification()
        }
    }

    /**
     * Used by the OCN Node to "stash" and "rewrite" the signature of a request if it needs to modify values
     */
    fun rewriteAndSign(valuesToSign: ValuesToSign<*>, rewriteFields: Map<String, Any?>): String? {
        if (isSigningActive()) {
            val notary = validateNotary()
            notary.stash(rewriteFields)
            notary.sign(valuesToSign, properties.privateKey!!)
            return notary.serialize()
        }
        return null
    }

    /**
     * Check notary exists. Throws UnsupportedOperationException if request has not yet been validated.
     */
    private fun validateNotary(): Notary {
        return notary
            ?: throw UnsupportedOperationException("Non-canonical method chaining: must call a validating method first")
    }
}
