package de.tum.cit.aet.hephaestus.agent.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxBackend;
import de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class SandboxMaintenanceConfigurationTest extends BaseUnitTest {

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final SandboxBackend backend = mock(SandboxBackend.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SandboxMaintenanceConfiguration.class, PropertiesConfiguration.class, ServerSchedulingConfig.class)
            .withBean(SandboxReconciler.class, () -> new SandboxReconciler(jobs, backend, meters, Clock.systemUTC()));

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @Test
    void shouldReconcileAtStartupWithoutWaitingForThePeriodicSweep() {
        runner.withPropertyValues("hephaestus.sandbox.reconciliation-initial-delay-seconds=86400")
                .run(context -> {
                    context.publishEvent(new ApplicationReadyEvent(
                            new SpringApplication(),
                            new String[0],
                            context.getSourceApplicationContext(),
                            Duration.ZERO));
                    assertThat(meters.get(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                                    .tag("outcome", "completed")
                                    .counter()
                                    .count())
                            .isEqualTo(1);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldScheduleReconciliationWhenTheServerRoleIsOnOrOff(boolean serverEnabled) {
        runner.withPropertyValues("hephaestus.runtime.server.enabled=" + serverEnabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                                    .flatMap(holder -> holder.getScheduledTasks().stream()))
                            .hasSize(1);
                });
    }

    @Test
    void shouldReconcileOrphansOnAWorkerWithoutServerScheduling() {
        runner.withPropertyValues(
                        "hephaestus.runtime.server.enabled=false",
                        "hephaestus.sandbox.reconciliation-initial-delay-seconds=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    await().atMost(Duration.ofSeconds(3))
                            .untilAsserted(() -> assertThat(meters.get(AgentMetrics.SANDBOX_RECONCILER_SWEEPS)
                                            .tag("outcome", "completed")
                                            .counter()
                                            .count())
                                    .isPositive());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotAccessTheRuntimeOrTheDatabaseInBuildOnlyProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledTaskRegistrar.class);
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
            verifyNoInteractions(jobs, backend);
        });
    }

    @Test
    void shouldNotScheduleMaintenanceWithoutTheWorkerRole() {
        runner.withPropertyValues("hephaestus.runtime.worker.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledTaskRegistrar.class);
            verifyNoInteractions(jobs, backend);
        });
    }

    @Test
    void shouldCancelMaintenanceWhenTheContextCloses() {
        var tasks = new ArrayList<ScheduledTask>();
        runner.run(context -> context.getBeansOfType(ScheduledTaskRegistrar.class)
                .values()
                .forEach(registrar -> tasks.addAll(registrar.getScheduledTasks())));
        assertThat(tasks)
                .hasSize(1)
                .allSatisfy(task -> assertThat(task.nextExecution()).isNull());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SandboxProperties.class)
    static class PropertiesConfiguration {}
}
