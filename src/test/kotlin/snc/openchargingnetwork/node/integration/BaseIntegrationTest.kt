package snc.openchargingnetwork.node.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import snc.openchargingnetwork.node.Application

/**
 * Base class for integration tests that provides common setup and teardown functionality.
 *
 * This class:
 * - Sets up the Spring Boot test context
 * - Uses the integration-test profile
 * - Provides access to the OCN node test helper
 * - Ensures proper cleanup after each test
 */
@SpringBootTest(
        classes = [Application::class, IntegrationTestConfig::class],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(locations = ["classpath:application-integration-test.properties"])
abstract class BaseIntegrationTest {

  protected lateinit var nodeHelper: OcnNodeTestHelper
  protected lateinit var runningNodes:
          Map<String, org.springframework.context.ConfigurableApplicationContext>

  @BeforeEach
  fun setUp() {
    nodeHelper = OcnNodeTestHelper()
    // Start with a clean state - no nodes running initially
    runningNodes = emptyMap()
  }

  @AfterEach
  fun tearDown() {
    // Ensure all nodes are stopped after each test
    nodeHelper.stopAllNodes()
  }

  /**
   * Starts two OCN nodes for testing
   *
   * @return Map containing the running application contexts
   */
  protected fun startTwoNodes():
          Map<String, org.springframework.context.ConfigurableApplicationContext> {
    runningNodes = nodeHelper.startTwoNodes()
    return runningNodes
  }

  /**
   * Starts a single OCN node for testing
   *
   * @param nodeId Unique identifier for the node
   * @param port Port number for the node
   * @param config Additional configuration properties
   * @return The application context of the started node
   */
  protected fun startNode(
          nodeId: String,
          port: Int,
          config: Map<String, Any> = emptyMap()
  ): org.springframework.context.ConfigurableApplicationContext {
    val context = nodeHelper.startNode(nodeId, port, config)
    runningNodes = mapOf(nodeId to context)
    return context
  }

  /**
   * Gets the URL for a specific node
   *
   * @param nodeId The ID of the node
   * @return The base URL for the node
   */
  protected fun getNodeUrl(nodeId: String): String {
    return nodeHelper.getNodeUrl(nodeId)
  }

  /**
   * Gets the API key for a specific node
   *
   * @param nodeId The ID of the node
   * @return The API key for the node
   */
  protected fun getNodeApiKey(nodeId: String): String {
    return nodeHelper.getNodeApiKey(nodeId)
  }
}
