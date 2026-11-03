package snc.openchargingnetwork.node.config

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
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
    fun `broadcast executor propagates the correlation id`() {
        MDC.put(TraceKeys.CORRELATION_ID, "cid-broadcast")

        val seen = broadcastExecutor.submitCompletable<String?> { MDC.get(TraceKeys.CORRELATION_ID) }

        assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("cid-broadcast")
    }
}
