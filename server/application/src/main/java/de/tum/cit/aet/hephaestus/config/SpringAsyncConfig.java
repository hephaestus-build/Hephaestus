package de.tum.cit.aet.hephaestus.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code @Async} advice is ordered outside the transaction advisor (so an {@code @Async @Transactional}
 * method opens its transaction on the pool thread, not the caller thread) and inside
 * {@code WorkspaceAgnosticAspect} at {@code HIGHEST_PRECEDENCE}. Both otherwise default to
 * {@code LOWEST_PRECEDENCE}, hence the explicit {@code HIGHEST_PRECEDENCE + 1}; many
 * {@code @Async @Transactional} listeners depend on it.
 *
 * <p>{@link #getAsyncExecutor()} pins the default executor to the bounded
 * {@link #applicationTaskExecutor}; with a second {@link AsyncTaskExecutor} bean present, type
 * resolution is ambiguous and Spring would otherwise fall back to an unbounded
 * {@code SimpleAsyncTaskExecutor}.
 */
@Configuration
@EnableAsync(order = Ordered.HIGHEST_PRECEDENCE + 1)
@Profile("!test")
public class SpringAsyncConfig implements AsyncConfigurer {

    @Bean(name = TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        // Stays below the datasource pool (spring.datasource.hikari.maximum-pool-size = 30) so an
        // async burst can't starve web requests of connections.
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("async-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // Let in-flight async DB work finish before the EntityManagerFactory closes on shutdown.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * The two feedback-preparation lanes and approved-feedback delivery, off the shared pool.
     *
     * <p>They shared {@link #applicationTaskExecutor} with every other {@code @Async} listener in the
     * tree, including the twenty-odd activity listeners a provider sync fans out. A concurrent sync
     * therefore decided whether a developer's feedback was prepared at all: the default
     * {@code AbortPolicy} throws once the 500-deep queue is full, and a rejected {@code AFTER_COMMIT}
     * event is not redelivered.
     *
     * <p>Small and deep: the work is short and bursty — one submission per finished review, one per
     * approval — so depth absorbs a burst better than width, and width here only competes for the same
     * Hikari connections the web tier needs. It can still reject; {@code
     * FeedbackLanePreparationSweeper} and {@code ApprovedFeedbackRecovery} are what make that survivable,
     * and this pool only makes it rare and unrelated to sync load.
     */
    @Bean(name = FeedbackLaneExecutor.BEAN_NAME)
    public AsyncTaskExecutor feedbackLaneExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("feedback-lane-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // Same reason as applicationTaskExecutor: let in-flight DB work finish before the
        // EntityManagerFactory closes.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    /**
     * The lane every record of a stored credential's readability is written on.
     *
     * <p>One worker and a shallow queue that discards: the record is best effort, a later read of the
     * same credential makes it again, and a stalled database must not turn a burst of failing reads
     * into retained work or into a second pooled connection per caller. Off {@link
     * #applicationTaskExecutor} so it neither queues behind the activity listeners a provider sync
     * fans out nor competes with them for the same 500-deep queue.
     *
     * <p>Waits only briefly on shutdown, so a record already in flight lands before the
     * EntityManagerFactory closes while a stalled one is abandoned like any other.
     */
    @Bean(name = CredentialReadabilityExecutor.BEAN_NAME)
    public AsyncTaskExecutor credentialReadabilityExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("credential-readability-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(2);
        return executor;
    }

    /** Long-running provider reconciliations must not queue behind or starve ordinary async work. */
    @Bean(name = "syncJobExecutor")
    public AsyncTaskExecutor syncJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("sync-job-");
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        // SyncJobService (a lifecycle phase above) records cooperative cancellation first; runners
        // observe it and unwind on their own. shutdownNow is the backstop that bounds context close
        // rather than leaking this non-daemon pool — it must never be the primary mechanism, since an
        // interrupt is ignored by a pgjdbc socket read here and destroys one on a virtual thread.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return applicationTaskExecutor();
    }

    /** Virtual-thread executor for blocking-I/O monitoring tasks. No graceful shutdown — use
     * {@link #applicationTaskExecutor} for DB work. */
    @Bean(name = "monitoringExecutor")
    @ConditionalOnMissingBean(name = "monitoringExecutor")
    public AsyncTaskExecutor monitoringExecutor() {
        TaskExecutorAdapter executor = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
        return executor;
    }
}
