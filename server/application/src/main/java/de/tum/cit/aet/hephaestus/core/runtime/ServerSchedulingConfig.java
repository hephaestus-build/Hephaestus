package de.tum.cit.aet.hephaestus.core.runtime;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AbstractDependsOnBeanFactoryPostProcessor;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring {@link EnableScheduling @EnableScheduling} only on the server-role pod.
 *
 * <p>This single switch silences every {@code @Scheduled} method on the worker and webhook
 * containers (which set {@code hephaestus.runtime.server.enabled=false}). It does nothing between
 * two server replicas: a job with a cluster-wide effect holds a {@code @SchedulerLock}
 * ({@link ShedLockConfig}), claims its rows, or acts only on this process's memory.
 *
 * <p>{@code matchIfMissing=true} preserves ADR 0005's DX invariant: zero env vars → full monolith
 * boots with scheduling enabled.
 * Test and build-only profiles exercise or inspect the application without automatic background work.
 *
 * <p>Worker-side sandbox and evidence-store maintenance is registered independently by
 * {@code SandboxMaintenanceConfiguration} and {@code JobEvidenceMaintenanceConfiguration}. Worker-only
 * deployments do not enable this server-wide scheduler; the stalled-write watchdog has its own thread so Docker cleanup cannot delay it.
 *
 * <p>On shutdown, closing the scheduler waits for running scheduled jobs
 * ({@code spring.task.scheduling.shutdown.await-termination}). A job has ended only when its
 * {@code @SchedulerLock} advice has released the lock. The scheduler therefore must close before the
 * database: {@link SchedulerClosesBeforeDatabase}.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test & !specs & !cds-training")
@ConditionalOnServerRole
@EnableScheduling
public class ServerSchedulingConfig {

    /**
     * A pool rather than Boot's virtual-thread scheduler, which runs every fixed-delay task on its one scheduler
     * thread: there, one long job (an Outline catch-up can run for up to an hour) held back every other fixed-delay
     * job, the sync lease heartbeat included, until another replica reaped a live sync. Here a long job holds one of
     * {@code spring.task.scheduling.pool.size} threads.
     */
    @Bean(name = "taskScheduler")
    TaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean
    static SchedulerClosesBeforeDatabase schedulerClosesBeforeDatabase() {
        return new SchedulerClosesBeforeDatabase();
    }

    /**
     * Makes the task scheduler depend on the database beans, so Spring destroys it first. A
     * running job keeps the JPA and JDBC access it needs to record its outcome and release its lock.
     */
    static final class SchedulerClosesBeforeDatabase extends AbstractDependsOnBeanFactoryPostProcessor {

        SchedulerClosesBeforeDatabase() {
            super(TaskScheduler.class, EntityManagerFactory.class, DataSource.class);
        }
    }
}
