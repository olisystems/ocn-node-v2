package snc.openchargingnetwork.node.config

import org.slf4j.MDC
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator

@Configuration
class MdcTaskDecoratorConfig {

    /** Applied by Spring Boot to the auto-configured executor that runs @Async methods. */
    @Bean
    fun mdcTaskDecorator(): TaskDecorator = MdcTaskDecorator()
}

class MdcTaskDecorator : TaskDecorator {

    override fun decorate(runnable: Runnable): Runnable {
        val context = MDC.getCopyOfContextMap()
        return Runnable {
            if (context == null) MDC.clear() else MDC.setContextMap(context)
            try {
                runnable.run()
            } finally {
                MDC.clear()
            }
        }
    }
}
