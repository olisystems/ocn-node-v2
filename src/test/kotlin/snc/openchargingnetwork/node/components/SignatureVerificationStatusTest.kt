package snc.openchargingnetwork.node.components

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpMethod
import shareandcharge.openchargingnetwork.notary.Notary
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.config.HaasProperties
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.models.OcpiHttpResponse
import snc.openchargingnetwork.node.models.OcnHeaders
import snc.openchargingnetwork.node.models.RegistryPartyDetailsBasic
import snc.openchargingnetwork.node.models.entities.OcnRules as EntityOcnRules
import snc.openchargingnetwork.node.models.exceptions.OcpiClientInvalidParametersException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.InterfaceRole
import snc.openchargingnetwork.node.models.ocpi.ModuleID
import snc.openchargingnetwork.node.models.ocpi.OcpiRequestVariables
import snc.openchargingnetwork.node.models.ocpi.OcpiResponse
import snc.openchargingnetwork.node.models.ocpi.SignatureVerificationStatus
import snc.openchargingnetwork.node.services.HubClientInfoService
import snc.openchargingnetwork.node.services.RegistryService
import snc.openchargingnetwork.node.services.RoutingService
import org.web3j.crypto.Credentials

class SignatureVerificationStatusTest {

    private lateinit var handler: OcpiMessageHandler
    private lateinit var routingService: RoutingService
    private lateinit var registryService: RegistryService
    private lateinit var properties: NodeProperties
    private lateinit var haasProperties: HaasProperties
    private lateinit var sender: BasicRole
    private lateinit var receiver: BasicRole
    private lateinit var request: OcpiRequestVariables

    @BeforeEach
    fun setUp() {
        routingService = mock()
        registryService = mock()

        properties = NodeProperties().apply {
            signatures = false
            url = "http://localhost:8080"
            privateKey = "1111111111111111111111111111111111111111111111111111111111111111"
        }

        haasProperties = HaasProperties().apply {
            enabled = false
            url = ""
        }

        sender = BasicRole("TST", "DE")
        receiver = BasicRole("RCV", "DE")

        request = OcpiRequestVariables(
            module = ModuleID.LOCATIONS,
            interfaceRole = InterfaceRole.SENDER,
            method = HttpMethod.GET,
            headers = OcnHeaders(
                authorization = "Token test-token",
                requestID = "test-request-id",
                correlationID = "test-correlation-id",
                sender = sender,
                receiver = receiver
            )
        )

        handler = OcpiMessageHandler(request, properties, haasProperties, routingService, registryService)
    }

    @Test
    fun `validateOcnSignature sets NOT_PRESENTED when signature is null and signing is disabled`() {
        val signedValues = request.toSignedValues()

        handler.validateOcnSignature(
            signature = null,
            signedValues = signedValues,
            signer = sender,
            receiver = null
        )

        assertThat(handler.verificationStatus).isEqualTo(SignatureVerificationStatus.NOT_PRESENTED)
    }

    @Test
    fun `validateOcnSignature sets VERIFIED when signature is valid and signing is disabled`() {
        val signedValues = request.toSignedValues()
        val validSignature = Notary().sign(signedValues, properties.privateKey!!).serialize()
        val signerAddress = Credentials.create(properties.privateKey).address
        whenever(registryService.getPartyDetails(sender)).thenReturn(
            RegistryPartyDetailsBasic(address = signerAddress, operator = signerAddress)
        )

        handler.validateOcnSignature(
            signature = validSignature,
            signedValues = signedValues,
            signer = sender,
            receiver = null
        )

        assertThat(handler.verificationStatus).isEqualTo(SignatureVerificationStatus.VERIFIED)
    }

    @Test
    fun `validateOcnSignature sets VERIFICATION_FAILED when signature is invalid and signing is disabled`() {
        val signedValues = request.toSignedValues()

        handler.validateOcnSignature(
            signature = "this-is-not-a-valid-signature",
            signedValues = signedValues,
            signer = sender,
            receiver = null
        )

        assertThat(handler.verificationStatus).isEqualTo(SignatureVerificationStatus.VERIFICATION_FAILED)
    }

    @Test
    fun `validateOcnSignature sets VERIFICATION_FAILED when registry lookup fails and signing is disabled`() {
        whenever(registryService.getPartyDetails(any())).thenThrow(RuntimeException("Party not found"))

        val signedValues = request.toSignedValues()

        handler.validateOcnSignature(
            signature = "invalid-base64-data",
            signedValues = signedValues,
            signer = sender,
            receiver = null
        )

        assertThat(handler.verificationStatus).isEqualTo(SignatureVerificationStatus.VERIFICATION_FAILED)
    }

    @Test
    fun `validateOcnSignature never throws when signing is disabled`() {
        val signedValues = request.toSignedValues()

        val signatures = listOf(
            null,
            "",
            "invalid",
            "dGVzdA==",
            "not-even-close-to-valid-signature-data-1234567890"
        )

        for (sig in signatures) {
            handler.validateOcnSignature(
                signature = sig,
                signedValues = signedValues,
                signer = sender,
                receiver = null
            )

            assertThat(handler.verificationStatus).isIn(
                SignatureVerificationStatus.VERIFIED,
                SignatureVerificationStatus.VERIFICATION_FAILED,
                SignatureVerificationStatus.NOT_PRESENTED
            )
        }
    }

    @Test
    fun `validateOcnSignature throws when signing is enabled and signature is invalid`() {
        properties.signatures = true
        whenever(routingService.getPlatformRules(any())).thenReturn(
            EntityOcnRules(signatures = true, blacklist = false, whitelist = false)
        )
        request = request.copy(
            headers = request.headers.copy(signature = "invalid-signature")
        )
        handler = OcpiMessageHandler(request, properties, haasProperties, routingService, registryService)

        val signedValues = request.toSignedValues()

        val exception = assertThrows<OcpiClientInvalidParametersException> {
            handler.validateOcnSignature(
                signature = "invalid-signature",
                signedValues = signedValues,
                signer = sender,
                receiver = receiver
            )
        }
        assertThat(exception.message).contains("Invalid signature")
    }

    @Test
    fun `OcpiResponse serializes ocn_verification_status correctly`() {
        val response = OcpiResponse<Unit>(
            statusCode = 1000,
            verificationStatus = "VERIFICATION_FAILED"
        )

        val httpClient = HttpClientComponent(properties)
        val json = httpClient.mapper.writeValueAsString(response)

        assertThat(json).contains("ocn_verification_status")
        assertThat(json).contains("VERIFICATION_FAILED")
    }

    @Test
    fun `OcpiResponse omits ocn_verification_status when null`() {
        val response = OcpiResponse<Unit>(statusCode = 1000)

        val httpClient = HttpClientComponent(properties)
        val json = httpClient.mapper.writeValueAsString(response)

        assertThat(json).doesNotContain("ocn_verification_status")
    }

    @Test
    fun `response handler adds request verification status after response signature validation`() {
        properties.signatures = true
        val receiverPrivateKey = "2222222222222222222222222222222222222222222222222222222222222222"
        val receiverAddress = Credentials.create(receiverPrivateKey).address
        request = request.copy(
            headers = request.headers.copy(
                signature = Notary().sign(request.toSignedValues(), properties.privateKey!!).serialize()
            )
        )
        whenever(routingService.getPlatformRules(sender)).thenReturn(
            EntityOcnRules(signatures = true, blacklist = false, whitelist = false)
        )
        whenever(routingService.isRoleKnown(receiver)).thenReturn(false)
        whenever(registryService.getPartyDetails(receiver)).thenReturn(
            RegistryPartyDetailsBasic(address = receiverAddress, operator = receiverAddress)
        )

        val responseBody = OcpiResponse<Unit>(statusCode = 1000)
        val response = OcpiHttpResponse<Unit>(statusCode = 200, headers = emptyMap(), body = responseBody)
        responseBody.signature = Notary().sign(response.toSignedValues(), receiverPrivateKey).serialize()

        val responseHandler = OcpiResponseHandlerBuilder(
            routingService,
            registryService,
            mock<HubClientInfoService>(),
            properties,
            haasProperties
        ).build(
            request,
            response,
            requestVerificationStatus = SignatureVerificationStatus.VERIFICATION_FAILED
        )

        assertThat(responseHandler.getResponse().body?.verificationStatus)
            .isEqualTo(SignatureVerificationStatus.VERIFICATION_FAILED.name)
    }
}
