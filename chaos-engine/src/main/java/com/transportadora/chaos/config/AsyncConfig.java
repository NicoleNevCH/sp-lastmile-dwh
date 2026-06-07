package com.transportadora.chaos.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Defines the pool that backs the simulated fleet. Each truck is submitted as
 * an {@code @Async} task and runs on one of these threads, so the size of the
 * pool is effectively "how many trucks are physically on the road at once".
 */
@Configuration
public class AsyncConfig {

    @Bean("truckExecutor")
    public TaskExecutor truckExecutor(
            @Value("${executor.core-pool-size:32}") int corePoolSize,
            @Value("${executor.max-pool-size:64}") int maxPoolSize,
            @Value("${executor.queue-capacity:1000}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("truck-");
        // If the queue saturates, run the task on the caller thread instead of
        // dropping it — we never want to silently lose simulated deliveries.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}
