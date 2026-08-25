package snc.openchargingnetwork.node.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import snc.openchargingnetwork.node.Application
import snc.openchargingnetwork.node.components.OcnRegistryComponent
import snc.openchargingnetwork.node.models.OcnRegistry
import snc.openchargingnetwork.node.models.Operator
import snc.openchargingnetwork.node.models.Party
import snc.openchargingnetwork.node.models.PaymentStatus
import snc.openchargingnetwork.node.models.CvStatus
import snc.openchargingnetwork.node.models.ocpi.Role
import org.mockito.Mockito

/**
 * Base class for integration tests that provides common setup and teardown functionality.
 *
 * This class:
 * - Sets up the Spring Boot test context
 * - Uses the integration-test profile
 * - Provides access to the OCN node test helper
 * - Ensures proper cleanup after each test
 * - Mocks OcnRegistryComponent to avoid external dependencies
 */
@Execution(ExecutionMode.SAME_THREAD)
@SpringBootTest(
        classes = [Application::class, IntegrationTestConfig::class],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(locations = ["classpath:application-integration-test.properties"])
abstract class BaseIntegrationTest {

  @MockBean
  protected lateinit var ocnRegistryComponent: OcnRegistryComponent

  protected lateinit var nodeHelper: OcnNodeTestHelper
  protected lateinit var runningNodes:
          Map<String, org.springframework.context.ConfigurableApplicationContext>

  @BeforeEach
  fun setUp() {
    nodeHelper = OcnNodeTestHelper()
    // Start with a clean state - no nodes running initially
    runningNodes = emptyMap()
    // Configure mock OcnRegistryComponent
    configureMockOcnRegistry()
  }

  /**
   * Configures the mock OcnRegistryComponent to return a mock registry
   */
  private fun configureMockOcnRegistry() {
    val mockParties = listOf(
      Party(
        id = "DE/CPO",
        countryCode = "DE",
        partyId = "CPO",
        partyAddress = "0x1234567890123456789012345678901234567890",
        roles = listOf(Role.CPO),
        name = "Test CPO",
        url = "http://localhost:8081/ocn",
        paymentStatus = PaymentStatus.PAID,
        cvStatus = CvStatus.VERIFIED,
        active = true,
        deleted = false,
        operator = Operator(
          id = "0x1234567890123456789012345678901234567890",
          domain = "test-cpo.com"
        )
      ),
      Party(
        id = "FR/EMS",
        countryCode = "FR",
        partyId = "EMS",
        partyAddress = "0x0987654321098765432109876543210987654321",
        roles = listOf(Role.EMSP),
        name = "Test EMS",
        url = "http://localhost:8082/ocn",
        paymentStatus = PaymentStatus.PAID,
        cvStatus = CvStatus.VERIFIED,
        active = true,
        deleted = false,
        operator = Operator(
          id = "0x0987654321098765432109876543210987654321",
          domain = "test-ems.com"
        )
      )
    )

    val mockOperators = listOf(
      Operator(
        id = "0x1234567890123456789012345678901234567890",
        domain = "test-cpo.com",
        parties = mockParties.filter { it.operator.id == "0x1234567890123456789012345678901234567890" }
      ),
      Operator(
        id = "0x0987654321098765432109876543210987654321",
        domain = "test-ems.com",
        parties = mockParties.filter { it.operator.id == "0x0987654321098765432109876543210987654321" }
      )
    )

    val mockRegistry = OcnRegistry(parties = mockParties, operators = mockOperators)
    Mockito.`when`(ocnRegistryComponent.getRegistry(Mockito.anyBoolean())).thenReturn(mockRegistry)
    Mockito.`when`(ocnRegistryComponent.getRegistry()).thenReturn(mockRegistry)
    Mockito.`when`(ocnRegistryComponent.findAllPartiesList()).thenReturn(mockParties)
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
