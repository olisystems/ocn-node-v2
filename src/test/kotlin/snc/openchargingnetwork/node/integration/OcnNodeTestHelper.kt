package snc.openchargingnetwork.node.integration

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import snc.openchargingnetwork.node.Application

/**
 * Helper class for starting and managing multiple OCN nodes for integration testing. Each node runs
 * on a different port with its own configuration.
 */
class OcnNodeTestHelper {

  private val runningNodes = mutableMapOf<String, ConfigurableApplicationContext>()

  /**
   * Starts a new OCN node with the specified configuration
   *
   * @param nodeId Unique identifier for the node
   * @param port Port number for the node to run on
   * @param config Additional configuration properties
   * @return The application context of the started node
   */
  fun startNode(
          nodeId: String,
          port: Int,
          config: Map<String, Any> = emptyMap()
  ): ConfigurableApplicationContext {
    val environment = StandardEnvironment()

    // Base configuration for integration tests
    val baseConfig =
            mapOf(
                    "spring.profiles.active" to "test",
                    "server.port" to port.toString(),
                    "server.address" to "127.0.0.1",
                    "spring.jpa.hibernate.ddl-auto" to "create-drop",
                    "spring.jpa.database-platform" to "org.hibernate.dialect.H2Dialect",
                    "spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults" to "false",
                    "spring.datasource.url" to "jdbc:h2:mem:${nodeId}-db;DB_CLOSE_DELAY=-1",
                    "spring.datasource.username" to "sa",
                    "spring.datasource.password" to "",
                    "spring.datasource.driver-class-name" to "org.h2.Driver",
                    "ocn.node.dev" to "true",
                    "ocn.node.signatures" to "false",
                    "ocn.node.url" to "http://127.0.0.1:$port",
                    "SERVER_URL" to "http://127.0.0.1:$port",
                    "ocn.node.apikey" to "integration-test-key-$nodeId",
                    "ocn.node.privatekey" to
                            "9df16a85d24a0dab6fb2bc5c57e1068ed47d56d7518e9b0eaf1712cae718ded6",
                    "ocn.node.apiPrefix" to "ocn-v2",
                    "ocn.hass.enabled" to "false",
                    "ocn.haas.url" to "http://localhost:9092/haas",
                    "logging.level.web" to "DEBUG",
                    "logging.level.snc.openchargingnetwork" to "DEBUG"
            )

    // Merge with provided config
    val mergedConfig = baseConfig + config

    environment.propertySources.addFirst(MapPropertySource("integration-test-config", mergedConfig))

    val application =
            SpringApplicationBuilder(Application::class.java)
                    .web(WebApplicationType.SERVLET)
                    .environment(environment)
                    .build()

    val context = application.run()
    runningNodes[nodeId] = context

    // Wait for the application to start
    waitForNodeToStart(port)

    return context
  }

  /**
   * Starts two OCN nodes with different configurations for integration testing
   *
   * @return Map containing the two application contexts
   */
  fun startTwoNodes(): Map<String, ConfigurableApplicationContext> {
    val node1Config =
            mapOf(
                    "ocn.node.apikey" to "integration-test-key-node1",
                    "ocn.node.privatekey" to
                            "9df16a85d24a0dab6fb2bc5c57e1068ed47d56d7518e9b0eaf1712cae718ded6"
            )

    val node2Config =
            mapOf(
                    "ocn.node.apikey" to "integration-test-key-node2",
                    "ocn.node.privatekey" to
                            "8ce17a94e35b9c1b7fb3bc6d58f2079fd46e47e8628f0f1bf0813bdf8271ef7"
            )

    val node1 = startNode("node1", 8081, node1Config)
    val node2 = startNode("node2", 8082, node2Config)

    return mapOf("node1" to node1, "node2" to node2)
  }

  /**
   * Stops a specific node
   *
   * @param nodeId The ID of the node to stop
   */
  fun stopNode(nodeId: String) {
    runningNodes[nodeId]?.let { context ->
      context.close()
      runningNodes.remove(nodeId)
    }
  }

  /** Stops all running nodes */
  fun stopAllNodes() {
    runningNodes.values.forEach { it.close() }
    runningNodes.clear()
  }

  /**
   * Gets the URL for a specific node
   *
   * @param nodeId The ID of the node
   * @return The base URL for the node
   */
  fun getNodeUrl(nodeId: String): String {
    return when (nodeId) {
      "node1" -> "http://127.0.0.1:8081"
      "node2" -> "http://127.0.0.1:8082"
      else -> throw IllegalArgumentException("Unknown node ID: $nodeId")
    }
  }

  /**
   * Gets the API key for a specific node
   *
   * @param nodeId The ID of the node
   * @return The API key for the node
   */
  fun getNodeApiKey(nodeId: String): String {
    return when (nodeId) {
      "node1" -> "integration-test-key-node1"
      "node2" -> "integration-test-key-node2"
      else -> throw IllegalArgumentException("Unknown node ID: $nodeId")
    }
  }

  private fun waitForNodeToStart(port: Int) {
    val future = CompletableFuture<Boolean>()

    Thread {
              var attempts = 0
              val maxAttempts = 30

              while (attempts < maxAttempts) {
                try {
                  val socket = java.net.Socket("127.0.0.1", port)
                  socket.close()
                  future.complete(true)
                  return@Thread
                } catch (e: Exception) {
                  attempts++
                  Thread.sleep(1000)
                }
              }
              future.complete(false)
            }
            .start()

    try {
      if (!future.get(30, TimeUnit.SECONDS)) {
        throw RuntimeException("Node failed to start on port $port within 30 seconds")
      }
    } catch (e: Exception) {
      throw RuntimeException("Timeout waiting for node to start on port $port", e)
    }
  }
}
