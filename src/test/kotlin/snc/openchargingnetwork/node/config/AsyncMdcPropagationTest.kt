package snc.openchargingnetwork.node.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.task.TaskExecutor
import org.springframework.test.context.ActiveProfiles
import snc.openchargingnetwork.node.tools.TraceKeys
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/** The executor @EnableAsync resolves by name must carry the MDC of the calling request. */
@SpringBootTest
@ActiveProfiles("test")
class AsyncMdcPropagationTest(
        @Autowired @Qualifier("taskExecutor") private val taskExecutor: TaskExecutor
) {

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `async executor propagates the correlation id`() {
        MDC.put(TraceKeys.CORRELATION_ID, "cid-async")
        val seen = CompletableFuture<String?>()

        taskExecutor.execute { seen.complete(MDC.get(TraceKeys.CORRELATION_ID)) }

        assertEquals("cid-async", seen.get(5, TimeUnit.SECONDS))
    }
}
