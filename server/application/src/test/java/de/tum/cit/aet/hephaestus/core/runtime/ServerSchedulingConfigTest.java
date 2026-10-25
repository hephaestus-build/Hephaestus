package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.util.ClassUtils;

class ServerSchedulingConfigTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues("spring.profiles.active=default")
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    TaskSchedulingAutoConfiguration.class,
                    ClassUtils.resolveClassName(
                            "org.springframework.modulith.moments.autoconfigure.MomentsAutoConfiguration", null)))
            .withUserConfiguration(ServerSchedulingConfig.class)
            .withBean(BackgroundWork.class);

    @Test
    void shouldScheduleBackgroundWorkForTheDefaultServerRole() {
        runner.run(context -> assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                        .flatMap(holder -> holder.getScheduledTasks().stream()))
                .hasSize(1));
    }

    @Test
    void shouldNotScheduleBackgroundWorkWhenTheServerRoleIsDisabled() {
        runner.withPropertyValues(RuntimeRole.SERVER_PROPERTY + "=false")
                .run(context -> assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                                .flatMap(holder -> holder.getScheduledTasks().stream()))
                        .isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "specs", "cds-training"})
    void shouldNotScheduleBackgroundWorkInTestAndBuildProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                            .flatMap(holder -> holder.getScheduledTasks().stream()))
                    .isEmpty();
        });
    }

    @Test
    void shouldKeepRunningFixedDelayJobsWhileAnotherJobRunsLong() {
        // Virtual threads as in production, where Boot's own scheduler runs every fixed-delay job on one thread.
        runner.withPropertyValues("spring.threads.virtual.enabled=true")
                .withBean(LongJob.class)
                .withBean(ShortJob.class)
                .run(context -> {
                    LongJob longJob = context.getBean(LongJob.class);
                    try {
                        assertThat(longJob.started.await(10, TimeUnit.SECONDS)).isTrue();
                        assertThat(context.getBean(ShortJob.class).runs.await(10, TimeUnit.SECONDS))
                                .isTrue();
                    } finally {
                        longJob.release.countDown();
                    }
                });
    }

    static class LongJob {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Scheduled(fixedDelay = 50)
        void run() throws InterruptedException {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
        }
    }

    static class ShortJob {
        final CountDownLatch runs = new CountDownLatch(3);

        @Scheduled(initialDelay = 200, fixedDelay = 50)
        void run() {
            runs.countDown();
        }
    }

    static class BackgroundWork {
        @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
        void run() {}
    }
}
