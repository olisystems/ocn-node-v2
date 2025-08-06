package snc.openchargingnetwork.node.integration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import snc.openchargingnetwork.node.Application

@SpringBootTest(
        classes = [Application::class],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(locations = ["classpath:application-integration-test.properties"])
class SimpleIntegrationTest {

  @Test
  fun `should start application context successfully`() {
    // This test just verifies that the application context can start
    assertThat(true).isTrue()
  }
}
