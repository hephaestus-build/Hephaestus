package de.tum.cit.aet.hephaestus.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.tenancy.WorkspaceAgnosticAspect;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Tag("unit")
class SpringAsyncConfigTest {

    @Test
    void asyncMethodsRunOnTheBoundedApplicationPool() {
        // setActiveProfiles("default") clears Gradle's "test" profile so @Profile("!test") applies.
        new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("default"))
                .withUserConfiguration(SpringAsyncConfig.class, AsyncProbe.class)
                .run(ctx -> {
                    String thread =
                            ctx.getBean(AsyncProbe.class).currentThread().get(5, TimeUnit.SECONDS);
                    // async-* = the bounded pool, not the SimpleAsyncTaskExecutor fallback.
                    assertThat(thread).startsWith("async-");
                });
    }

    @Test
    void asyncAdviceOrderedBetweenTenancyAspectAndTransaction() {
        // The advice chain tie is nondeterministic, so this can't be asserted behaviorally. Assert the
        // invariant against the orders production actually declares — reading the aspect's @Order rather
        // than restating it, so moving either end fails this test.
        int asyncOrder =
                SpringAsyncConfig.class.getAnnotation(EnableAsync.class).order();
        int tenancyAspectOrder =
                WorkspaceAgnosticAspect.class.getAnnotation(Order.class).value();
        // Spring Boot's TransactionAutoConfiguration enables tx management at the default advisor order.
        int transactionAdvisorOrder = Ordered.LOWEST_PRECEDENCE;

        // Immediately inside the tenancy aspect: it must bind the workspace before the async hop.
        assertThat(asyncOrder).isEqualTo(tenancyAspectOrder + 1);
        // Outside the tx advisor: an @Async @Transactional method must switch threads before the tx opens,
        // otherwise the transaction would be started on the caller's thread and handed across the hop.
        assertThat(asyncOrder).isLessThan(transactionAdvisorOrder);
    }

    @Test
    void syncJobExecutorInterruptsRunningTasksOnShutdown() throws Exception {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new SpringAsyncConfig().syncJobExecutor();
        executor.initialize();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        CountDownLatch executorDestroyed = new CountDownLatch(1);
        executor.execute(() -> {
            taskStarted.countDown();
            try {
                releaseTask.await();
            } catch (InterruptedException e) {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertThat(taskStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread.ofVirtual().start(() -> {
            executor.destroy();
            executorDestroyed.countDown();
        });

        try {
            assertThat(executorDestroyed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(taskInterrupted.getCount()).isZero();
        } finally {
            releaseTask.countDown();
        }
    }

    static class AsyncProbe {

        @Async
        public CompletableFuture<String> currentThread() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }
    }
}
