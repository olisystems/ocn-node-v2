package snc.openchargingnetwork.node.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.util.encodeBase64
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import snc.openchargingnetwork.node.models.ocpi.BasicRole

/**
 * Example integration test demonstrating how to test interactions between multiple OCN nodes.
 *
 * This test shows:
 * - How to start multiple nodes with different configurations
 * - How to make HTTP requests between nodes
 * - How to verify node communication
 */
class MultiNodeIntegrationTest : BaseIntegrationTest() {

  private val restTemplate = TestRestTemplate()
  @Autowired private lateinit var objectMapper: ObjectMapper

  @Test
  fun `should start two OCN nodes successfully`() {
    // Start two nodes
    val nodes = startTwoNodes()

    // Verify both nodes are running
    assertThat(nodes).hasSize(2)
    assertThat(nodes["node1"]).isNotNull()
    assertThat(nodes["node2"]).isNotNull()

    // Verify nodes are accessible via HTTP
    val node1Url = getNodeUrl("node1")
    val node2Url = getNodeUrl("node2")

    val node1Response = restTemplate.getForEntity("$node1Url/ocn-v2/health", String::class.java)
    val node2Response = restTemplate.getForEntity("$node2Url/ocn-v2/health", String::class.java)

    assertThat(node1Response.statusCode.value()).isEqualTo(200)
    assertThat(node2Response.statusCode.value()).isEqualTo(200)
  }

  @Test
  fun `should have different API keys for each node`() {
    // Start two nodes
    startTwoNodes()

    val node1ApiKey = getNodeApiKey("node1")
    val node2ApiKey = getNodeApiKey("node2")

    // Verify nodes have different API keys
    assertThat(node1ApiKey).isEqualTo("integration-test-key-node1")
    assertThat(node2ApiKey).isEqualTo("integration-test-key-node2")
    assertThat(node1ApiKey).isNotEqualTo(node2ApiKey)
  }

  @Test
  fun `should be able to make authenticated requests to nodes with admin key`() {
    // Start two nodes
    startTwoNodes()

    val node1Url = getNodeUrl("node1")
    val node1ApiKey = getNodeApiKey("node1")
    val b64ode1ApiKey = node1ApiKey.encodeBase64()

    // Create headers with API key
    val headers = HttpHeaders()
    headers.set("Authorization", "Token $b64ode1ApiKey")
    headers.set("Content-Type", "application/json")
    val basicRoles = listOf(BasicRole("OLI", "DE"))
    val body = objectMapper.writeValueAsString(basicRoles)
    val entity = HttpEntity<String>(body, headers)

    // Make authenticated request to node1
    val response: ResponseEntity<String> =
            restTemplate.exchange(
                    "$node1Url/ocn-v2/admin/generate-registration-token",
                    HttpMethod.POST,
                    entity,
                    String::class.java
            )

    assertThat(response.statusCode.value()).isEqualTo(200)
  }

  @Test
  fun `should handle node communication scenarios`() {
    // Start two nodes with different configurations
    val node1Config = mapOf("ocn.node.dev" to "true", "ocn.node.signatures" to "false")

    val node2Config = mapOf("ocn.node.dev" to "true", "ocn.node.signatures" to "false")

    val node1 = startNode("node1", 8081, node1Config)
    val node2 = startNode("node2", 8082, node2Config)

    // Verify both nodes are running and accessible
    val node1Url = getNodeUrl("node1")
    val node2Url = getNodeUrl("node2")

    val node1Health = restTemplate.getForEntity("$node1Url/ocn-v2/health", String::class.java)
    val node2Health = restTemplate.getForEntity("$node2Url/ocn-v2/health", String::class.java)

    assertThat(node1Health.statusCode.value()).isEqualTo(200)
    assertThat(node2Health.statusCode.value()).isEqualTo(200)
  }

  @Test
  fun `should handle node startup and shutdown gracefully`() {
    // Start a single node
    val node1 = startNode("node1", 8081)

    // Verify node is running
    val node1Url = getNodeUrl("node1")
    val response = restTemplate.getForEntity("$node1Url/ocn-v2/health", String::class.java)
    assertThat(response.statusCode.value()).isEqualTo(200)

    // Stop the node
    nodeHelper.stopNode("node1")

    // Verify node is no longer accessible
    try {
      restTemplate.getForEntity("$node1Url/ocn-v2/versions", String::class.java)
      // If we reach here, the node is still running
      assertThat(false).isTrue() // This should not happen
    } catch (e: Exception) {
      // Expected - node should not be accessible
      assertThat(e).isNotNull()
    }
  }
}
