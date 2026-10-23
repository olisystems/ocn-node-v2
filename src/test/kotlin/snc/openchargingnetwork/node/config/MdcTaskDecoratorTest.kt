package snc.openchargingnetwork.node.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import snc.openchargingnetwork.node.tools.TraceKeys
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MdcTaskDecoratorTest {

    @AfterEach
    fun clearMdc() = MDC.clear()

    @Test
    fun `copies the submitting thread's MDC to the worker and clears it afterwards`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            MDC.put(TraceKeys.CORRELATION_ID, "cid-1")
            var seen: String? = null
            val task = MdcTaskDecorator().decorate { seen = MDC.get(TraceKeys.CORRELATION_ID) }
            MDC.clear()

            executor.submit(task).get()
            val leftOver = executor.submit<Map<String, String>?> { MDC.getCopyOfContextMap() }.get()

            assertEquals("cid-1", seen)
            assertTrue(leftOver.isNullOrEmpty())
        } finally {
            executor.shutdownNow()
        }
    }
}
