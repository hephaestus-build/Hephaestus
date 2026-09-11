package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.leaderboard.LeaderboardTaskScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.util.ClassUtils;

class ServerSchedulingConfigTest extends BaseUnitTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(ClassUtils.resolveClassName(
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
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotScheduleBackgroundWorkInBuildProfiles(String profile) {
        runner.withUserConfiguration(LeaderboardTaskScheduler.class)
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(LeaderboardTaskScheduler.class);
                    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                                    .flatMap(holder -> holder.getScheduledTasks().stream()))
                            .isEmpty();
                });
    }

    static class BackgroundWork {
        @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
        void run() {}
    }
}
