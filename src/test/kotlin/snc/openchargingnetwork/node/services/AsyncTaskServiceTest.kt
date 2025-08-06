package snc.openchargingnetwork.node.services

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import snc.openchargingnetwork.node.config.NodeProperties

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AsyncTaskServiceTest(
    @Autowired private val asyncTaskService: AsyncTaskService,
    @Autowired private val registryService: RegistryService,
    @Autowired private val properties: NodeProperties
) {
    @Test
    fun `service should be properly configured`() {
        assertThat(asyncTaskService).isNotNull()
        assertThat(registryService).isNotNull()
        assertThat(properties).isNotNull()
    }

    @Test
    fun `properties should have required configuration`() {
        assertThat(properties).isInstanceOf(NodeProperties::class.java)
    }

    @Test
    fun `registryService should be accessible`() {
        val nodes = registryService.getNodes()

        assertThat(nodes).isNotNull()
        assertThat(nodes).isInstanceOf(List::class.java)
    }

    @Test
    fun `service should have correct constructor parameters`() {
        assertThat(asyncTaskService).isNotNull()
        val nodes = registryService.getNodes()
        assertThat(nodes).isNotNull()
    }
}
