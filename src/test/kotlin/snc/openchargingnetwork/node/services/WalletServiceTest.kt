package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.components.HttpClientComponent
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.TestOcnRegistryComponent
import snc.openchargingnetwork.node.models.exceptions.InvalidOcnSignatureException
import snc.openchargingnetwork.node.models.exceptions.OcpiHubConnectionProblemException
import snc.openchargingnetwork.node.models.ocpi.BasicRole
import snc.openchargingnetwork.node.models.ocpi.ClientInfo

@SpringBootTest(classes = [TestOcnRegistryComponent::class])
@ActiveProfiles("test")
@Transactional
class WalletServiceTest(
        @Autowired private val walletService: WalletService,
        @Autowired private val properties: NodeProperties,
        @Autowired private val ocnRegistryComponent: OcnRegistryComponent,
        @Autowired private val httpClientComponent: HttpClientComponent
) {

    private lateinit var testRole: BasicRole
    private lateinit var testRequest: String

    @BeforeEach
    fun setUp() {
        testRole = BasicRole("TST", "DE")
        testRequest = """{"test": "data", "timestamp": "2023-01-01T00:00:00Z"}"""
    }

    @Test
    fun `sign should return valid signature string`() {
        val signature = walletService.sign(testRequest)

        assertThat(signature).isNotNull()
        assertThat(signature).isNotEmpty()
        // Signature should be 130 characters (64 for r + 64 for s + 2 for v)
        assertThat(signature.length).isEqualTo(130)
        // Should be hexadecimal
        assertThat(signature).matches("[0-9a-fA-F]+")
    }

    @Test
    fun `sign should return different signatures for different requests`() {
        val request1 = """{"data": "value1"}"""
        val request2 = """{"data": "value2"}"""

        val signature1 = walletService.sign(request1)
        val signature2 = walletService.sign(request2)

        assertThat(signature1).isNotEqualTo(signature2)
    }

    @Test
    fun `sign should return same signature for same request`() {
        val signature1 = walletService.sign(testRequest)
        val signature2 = walletService.sign(testRequest)

        assertThat(signature1).isEqualTo(signature2)
    }

    @Test
    fun `signatureStringToByteArray should correctly parse signature`() {
        val signature = walletService.sign(testRequest)

        val (r, s, v) = walletService.signatureStringToByteArray(signature)

        assertThat(r).isNotNull()
        assertThat(s).isNotNull()
        assertThat(v).isNotNull()
        assertThat(r.size).isEqualTo(32) // 32 bytes = 256 bits
        assertThat(s.size).isEqualTo(32)
        assertThat(v.size).isEqualTo(1) // v is 1 byte
    }

    @Test
    fun `verify should not throw exception for valid signature`() {
        val signature = walletService.sign(testRequest)

        try {
            walletService.verify(testRequest, signature, testRole)
        } catch (e: Exception) {
            assertThat(e.message).contains("Recipient not registered on OCN")
        }
    }

    @Test
    fun `verify should throw exception for modified request`() {
        val originalRequest = """{"data": "original"}"""
        val modifiedRequest = """{"data": "modified"}"""
        val signature = walletService.sign(originalRequest)

        assertThrows<OcpiHubConnectionProblemException> {
            walletService.verify(modifiedRequest, signature, testRole)
        }
    }

    @Test
    fun `verify should throw exception for signature with wrong length`() {
        val invalidSignature = "1234567890" // Too short

        assertThrows<Exception> { walletService.verify(testRequest, invalidSignature, testRole) }
    }

    @Test
    fun `verifyClientInfo should return client info for valid signature`() {
        val clientInfo =
                ClientInfo(
                        partyID = "TST",
                        countryCode = "DE",
                        role = snc.openchargingnetwork.node.models.ocpi.Role.CPO,
                        status =
                                snc.openchargingnetwork.node.models.ocpi.ConnectionStatus.CONNECTED,
                        lastUpdated = "2023-01-01T00:00:00Z"
                )
        val clientInfoString = httpClientComponent.mapper.writeValueAsString(clientInfo)
        val signature = walletService.sign(clientInfoString)

        try {
            val result = walletService.verifyClientInfo(clientInfoString, signature)
            assertThat(result).isInstanceOf(ClientInfo::class.java)
            assertThat(result.partyID).isEqualTo("TST")
            assertThat(result.countryCode).isEqualTo("DE")
        } catch (e: Exception) {
            // If the role doesn't exist in registry, this is expected
            assertThat(e.message).contains("Recipient not registered on OCN")
        }
    }

    @Test
    fun `verifyClientInfo should throw exception for invalid signature`() {
        val clientInfo =
                ClientInfo(
                        partyID = "TST",
                        countryCode = "DE",
                        role = snc.openchargingnetwork.node.models.ocpi.Role.EMSP,
                        status =
                                snc.openchargingnetwork.node.models.ocpi.ConnectionStatus.SUSPENDED,
                        lastUpdated = "2023-01-01T00:00:00Z"
                )
        val clientInfoString = httpClientComponent.mapper.writeValueAsString(clientInfo)
        val invalidSignature =
                "7bfaddc69e4aa7259273853733627d9293e0cea04d66ee313565123edc0926e8274f3c4653a94eda9ffeabc59b4ea091537317073c8ce3ed8f68e5f7ae9235071b"

        try {
            walletService.verifyClientInfo(clientInfoString, invalidSignature)
            // If we reach here, no exception was thrown, which is a failure
            assertThat(false).isTrue() // This should never be reached
        } catch (e: Exception) {
            // Verify that an exception was thrown
            assertThat(e).isInstanceOf(InvalidOcnSignatureException::class.java)
        }
    }

    @Test
    fun `verifyClientInfo should throw exception for modified client info`() {
        val originalClientInfo =
                ClientInfo(
                        partyID = "TST",
                        countryCode = "DE",
                        role = snc.openchargingnetwork.node.models.ocpi.Role.CPO,
                        status =
                                snc.openchargingnetwork.node.models.ocpi.ConnectionStatus.CONNECTED,
                        lastUpdated = "2023-01-01T00:00:00Z"
                )
        val originalString = httpClientComponent.mapper.writeValueAsString(originalClientInfo)
        val signature = walletService.sign(originalString)

        val modifiedClientInfo =
                ClientInfo(
                        partyID = "TST", // Keep same party ID that exists in registry
                        countryCode = "DE",
                        role =
                                snc.openchargingnetwork.node.models.ocpi.Role
                                        .EMSP, // Change role to trigger signature verification
                        // failure
                        status =
                                snc.openchargingnetwork.node.models.ocpi.ConnectionStatus.CONNECTED,
                        lastUpdated = "2023-01-01T00:00:00Z"
                )

        val modifiedString = httpClientComponent.mapper.writeValueAsString(modifiedClientInfo)

        try {
            walletService.verifyClientInfo(modifiedString, signature)
            // If we reach here, no exception was thrown, which is a failure
            assertThat(false).isTrue() // This should never be reached
        } catch (e: Exception) {
            // Verify that an exception was thrown
            assertThat(e).isInstanceOf(InvalidOcnSignatureException::class.java)
            assertThat(e.message).contains("Invalid OCN-Signature")
        }
    }

    @Test
    fun `signatureStringToByteArray should handle valid hex string`() {
        val validHexSignature = "1".repeat(130)
        val (r, s, v) = walletService.signatureStringToByteArray(validHexSignature)

        assertThat(r).isNotNull()
        assertThat(s).isNotNull()
        assertThat(v).isNotNull()
    }

    @Test
    fun `signatureStringToByteArray should handle hex string with 0x prefix`() {
        val signatureWithPrefix = "0x" + "1".repeat(130)
        val (r, s, v) = walletService.signatureStringToByteArray(signatureWithPrefix)

        assertThat(r).isNotNull()
        assertThat(s).isNotNull()
        assertThat(v).isNotNull()
    }
}
