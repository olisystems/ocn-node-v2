package snc.openchargingnetwork.node.config

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Gives hub broadcast deliveries their own threads. Both executors come from Spring Boot's
 * builder, so spring.task.execution.* and the MDC task decorator apply to each of them.
 */
@Configuration
class BroadcastExecutorConfig {

    companion object {
        const val BROADCAST_TASK_EXECUTOR = "broadcastTaskExecutor"
    }

    /**
     * Spring Boot stops auto-configuring this executor as soon as any other Executor bean exists,
     * so it is declared here the same way Boot does it, under the names @Async and Spring MVC look
     * it up by. Primary, so code that asks for an executor by type keeps getting this one.
     */
    @Bean(
        name = [
            TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME,
            AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME
        ]
    )
    @Lazy
    @Primary
    fun applicationTaskExecutor(builder: ThreadPoolTaskExecutorBuilder): ThreadPoolTaskExecutor = builder.build()

    /**
     * One task per broadcast receiver. A fixed pool rather than Boot's grow-after-the-queue-fills
     * default, so up to [NodeProperties.broadcastPoolSize] receivers are called at once and idle
     * threads are let go.
     */
    @Bean(BROADCAST_TASK_EXECUTOR)
    fun broadcastTaskExecutor(
        builder: ThreadPoolTaskExecutorBuilder,
        properties: NodeProperties
    ): ThreadPoolTaskExecutor =
        builder
            .corePoolSize(properties.broadcastPoolSize)
            .maxPoolSize(properties.broadcastPoolSize)
            .allowCoreThreadTimeOut(true)
            .queueCapacity(properties.broadcastQueueCapacity)
            .threadNamePrefix("broadcast-")
            .build()
}
