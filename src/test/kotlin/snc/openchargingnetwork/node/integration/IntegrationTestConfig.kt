package snc.openchargingnetwork.node.integration

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import snc.openchargingnetwork.node.config.DataSourceProperties
import snc.openchargingnetwork.node.config.NodeProperties

@TestConfiguration
@ActiveProfiles("test")
class IntegrationTestConfig {

  @Bean
  @Primary
  fun integrationNodeProperties(): NodeProperties {
    return NodeProperties().apply {
      dev = true
      signatures = false
      url = "http://localhost:8080"
      apikey = "integration-test-key"
      privateKey = "9df16a85d24a0dab6fb2bc5c57e1068ed47d56d7518e9b0eaf1712cae718ded6"
      apiPrefix = "ocn-v2"
    }
  }

  @Bean
  @Primary
  fun integrationDataSourceProperties(): DataSourceProperties {
    return DataSourceProperties().apply {
      url = "jdbc:h2:mem:integration-test-db;DB_CLOSE_DELAY=-1"
      username = "sa"
      password = ""
    }
  }
}
