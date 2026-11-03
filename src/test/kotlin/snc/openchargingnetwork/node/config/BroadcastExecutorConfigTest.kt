package snc.openchargingnetwork.node.config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils
import snc.openchargingnetwork.node.services.ModuleNotificationService
import snc.openchargingnetwork.node.tools.TraceKeys

/** Hub broadcasts run on their own executor, apart from the one @Async methods share. */
@SpringBootTest
@ActiveProfiles("test")
class BroadcastExecutorConfigTest(
        @Autowired @Qualifier(BroadcastExecutorConfig.BROADCAST_TASK_EXECUTOR)
        private val broadcastExecutor: ThreadPoolTaskExecutor,
        @Autowired @Qualifier("taskExecutor") private val asyncExecutor: ThreadPoolTaskExecutor,
        @Autowired private val nodeProperties: NodeProperties,
        @Autowired private val moduleNotificationService: ModuleNotificationService
) {

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `broadcasts use the broadcast executor, sized by the node properties`() {
        val service = AopTestUtils.getTargetObject<ModuleNotificationService>(moduleNotificationService)
        val serviceExecutor = ReflectionTestUtils.getField(service, "taskExecutor")

        assertThat(serviceExecutor).isSameAs(broadcastExecutor).isNotSameAs(asyncExecutor)
        assertThat(broadcastExecutor.corePoolSize).isEqualTo(nodeProperties.broadcastPoolSize)
        assertThat(broadcastExecutor.maxPoolSize).isEqualTo(nodeProperties.broadcastPoolSize)
        assertThat(broadcastExecutor.queueCapacity).isEqualTo(nodeProperties.broadcastQueueCapacity)
    }

    @Test
    fun `a delivery starts while every async thread is busy`() {
        val release = CountDownLatch(1)
        repeat(asyncExecutor.corePoolSize) { asyncExecutor.execute { release.await() } }
        try {
            val thread = broadcastExecutor.submitCompletable<String> { Thread.currentThread().name }

            assertThat(thread.get(5, TimeUnit.SECONDS)).startsWith("broadcast-")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a negative queue capacity or an empty pool stops the node from starting`() {
        settings("ocn.node.broadcastQueueCapacity=-1").run { context ->
            assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessage("ocn.node.broadcastQueueCapacity must be 0 or more, was -1")
        }
        settings("ocn.node.broadcastPoolSize=0").run { context ->
            assertThat(context.startupFailure)
                    .rootCause()
                    .hasMessage("ocn.node.broadcastPoolSize must be at least 1, was 0")
        }
    }

    @Test
    fun `a queue capacity of 0 means no queue`() {
        settings("ocn.node.broadcastQueueCapacity=0").run { context ->
            assertThat(context.getBean(BroadcastExecutorConfig.BROADCAST_TASK_EXECUTOR, ThreadPoolTaskExecutor::class.java).queueCapacity)
                    .isEqualTo(0)
        }
    }

    /** Only the executor beans and the properties they read, started with [properties]. */
    private fun settings(vararg properties: String) =
            ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration::class.java))
                    .withUserConfiguration(BroadcastExecutorConfig::class.java, NodePropertiesConfig::class.java)
                    .withPropertyValues(*properties)

    @EnableConfigurationProperties(NodeProperties::class)
    private class NodePropertiesConfig

    @Test
    fun `broadcast executor propagates the correlation id`() {
        MDC.put(TraceKeys.CORRELATION_ID, "cid-broadcast")

        val seen = broadcastExecutor.submitCompletable<String?> { MDC.get(TraceKeys.CORRELATION_ID) }

        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("cid-broadcast")
    }
}
