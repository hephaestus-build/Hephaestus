package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.config.CredentialReadabilityExecutor;
import de.tum.cit.aet.hephaestus.config.FeedbackLaneExecutor;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/** Synchronous dispatch keeps event-listener database writes inside the test that triggered them. */
@Configuration
@EnableAsync
@Profile("test")
public class TestAsyncConfiguration implements AsyncConfigurer {

    private final AsyncTaskExecutor syncExecutor = new TaskExecutorAdapter(new SyncTaskExecutor());

    @Override
    public Executor getAsyncExecutor() {
        return syncExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new SimpleAsyncUncaughtExceptionHandler();
    }

    @Bean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    @Primary
    public AsyncTaskExecutor taskExecutor() {
        return syncExecutor;
    }

    @Bean(name = "syncJobExecutor")
    public AsyncTaskExecutor syncJobExecutor() {
        return syncExecutor;
    }

    // Qualified @Async methods resolve these executor bean names directly.
    @Bean(name = FeedbackLaneExecutor.BEAN_NAME)
    public AsyncTaskExecutor feedbackLaneExecutor() {
        return syncExecutor;
    }

    @Bean(name = CredentialReadabilityExecutor.BEAN_NAME)
    public AsyncTaskExecutor credentialReadabilityExecutor() {
        return syncExecutor;
    }

    // External synchronization is exercised explicitly by its integration tests, not during fixture setup.
    @Bean(name = "monitoringExecutor")
    public AsyncTaskExecutor monitoringExecutor() {
        return new NoOpAsyncTaskExecutor();
    }

    private static class NoOpAsyncTaskExecutor implements AsyncTaskExecutor {

        @Override
        public void execute(Runnable task) {}

        @Override
        public Future<?> submit(Runnable task) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T extends @Nullable Object> Future<T> submit(Callable<T> task) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.cancel(false);
            return future;
        }
    }
}
