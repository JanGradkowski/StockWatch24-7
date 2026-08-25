package org.example.stockwatch247.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class TechnicalOutlookAsyncConfiguration {

    @Bean(name = "technicalOutlookExecutor")
    public Executor technicalOutlookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // ThreadPoolExecutor grows beyond its core only after the queue fills.
        // Equal core/max sizes therefore provide real bounded concurrency for
        // ordinary refresh traffic instead of an effectively single worker.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("technical-outlook-refresh-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
