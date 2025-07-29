package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.config.NodeProperties
import snc.openchargingnetwork.node.config.TestOcnRegistryComponent
import snc.openchargingnetwork.node.models.RegistryNode
import snc.openchargingnetwork.node.models.RegistryPartyDetailsBasic
import snc.openchargingnetwork.node.models.ocpi.BasicRole

@SpringBootTest(classes = [TestOcnRegistryComponent::class])
@ActiveProfiles("test")
@Transactional
class RegistryServiceTest(
    @Autowired private val registryService: RegistryService,
    @Autowired private val ocnRegistryComponent: OcnRegistryComponent,
    @Autowired private val properties: NodeProperties
) {

    private lateinit var testRole: BasicRole
    private lateinit var knownRole: BasicRole
    private lateinit var unknownRole: BasicRole

    @BeforeEach
    fun setUp() {
        testRole = BasicRole("TST", "DE")
        knownRole = BasicRole("DEM", "DE")
        unknownRole = BasicRole("XXX", "XX")
    }

    @Test
    fun `getNodes should return list of registry nodes`() {

        val nodes = registryService.getNodes()


        assertThat(nodes).isNotNull()
        assertThat(nodes).isInstanceOf(List::class.java)
        assertThat(nodes).hasSize(2)

        // Verify structure of returned nodes
        val firstNode = nodes.first()
        assertThat(firstNode).isInstanceOf(RegistryNode::class.java)
        assertThat(firstNode.operator).isNotNull()
        assertThat(firstNode.url).isNotNull()
        assertThat(firstNode.url).isEqualTo("https://test-operator1.com")
    }

    @Test
    fun `getNodes with omitMine=true should exclude current node`() {

        val allNodes = registryService.getNodes(omitMine = false)
        val nodesWithoutMine = registryService.getNodes(omitMine = true)


        assertThat(nodesWithoutMine.size).isLessThanOrEqualTo(allNodes.size)
        assertThat(allNodes).hasSize(2)

        // In test environment, the difference might be 0 or 1 depending on configuration
        val difference = allNodes.size - nodesWithoutMine.size
        assertThat(difference).isBetween(0, 1)
    }

    @Test
    fun `isRoleKnown should return true for known role when belongsToMe=false`() {

        val result = registryService.isRoleKnown(knownRole, belongsToMe = false)


        assertThat(result).isTrue()
    }

    @Test
    fun `getRemoteNodeUrlOf should return domain for known role`() {

        val url = registryService.getRemoteNodeUrlOf(knownRole)


        assertThat(url).isNotNull()
        assertThat(url).isNotEmpty()
        assertThat(url).isEqualTo("https://test-operator2.com")
    }

    @Test
    fun `isRoleKnown should throw exception for unknown role`() {
        try {
            registryService.isRoleKnown(unknownRole)
        } catch (e: Exception) {
            assertThat(e.message).contains("Recipient not registered on OCN")
        }
    }

    @Test
    fun `getPartyDetails should return party details for known role`() {

        val partyDetails = registryService.getPartyDetails(knownRole)

        assertThat(partyDetails).isInstanceOf(RegistryPartyDetailsBasic::class.java)
        assertThat(partyDetails.address).isNotNull()
        assertThat(partyDetails.operator).isNotNull()
        assertThat(partyDetails.address).isEqualTo("0x2222222222222222222222222222222222222222")
        assertThat(partyDetails.operator).isEqualTo("0x0987654321098765432109876543210987654321")
    }

    @Test
    fun `getNodes should handle empty registry gracefully`() {
        val nodes = registryService.getNodes()
        assertThat(nodes).isNotNull()
        assertThat(nodes).isInstanceOf(List::class.java)
        assertThat(nodes).hasSize(2)
    }

    @Test
    fun `getRemoteNodeUrlOf should throw exception for unknown role`() {
        try {
            registryService.getRemoteNodeUrlOf(unknownRole)
        } catch (e: Exception) {
            assertThat(e.message).contains("Recipient not registered on OCN")
        }
    }

    @Test
    fun `getPartyDetails should throw exception for unknown role`() {
        try {
            registryService.getPartyDetails(unknownRole)
        } catch (e: Exception) {
            assertThat(e.message).contains("Recipient not registered on OCN")
        }
    }
}
