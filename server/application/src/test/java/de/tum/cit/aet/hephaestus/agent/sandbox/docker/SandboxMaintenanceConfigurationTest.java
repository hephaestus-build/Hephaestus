package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.InteractiveSandboxMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.InteractiveSandboxRegistry;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive.StdinWriteWatchdog;
import de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class SandboxMaintenanceConfigurationTest extends BaseUnitTest {

    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final SandboxContainerManager containers = mock(SandboxContainerManager.class);
    private final SandboxNetworkManager networks = mock(SandboxNetworkManager.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StdinWriteWatchdog watchdog = new StdinWriteWatchdog();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SandboxMaintenanceConfiguration.class, PropertiesConfiguration.class, ServerSchedulingConfig.class)
            .withBean(StdinWriteWatchdog.class, () -> watchdog)
            .withBean(NativeGitVolumeReconciler.class, () -> mock(NativeGitVolumeReconciler.class))
            .withBean(
                    SandboxReconciler.class,
                    () -> new SandboxReconciler(
                            jobs,
                            containers,
                            networks,
                            org.mockito.Mockito.mock(SandboxVolumeManager.class),
                            meters,
                            Clock.systemUTC()))
            .withBean(
                    InteractiveSandboxRegistry.class,
                    () -> new InteractiveSandboxRegistry(
                            new InteractiveSandboxProperties(300, 1, 1, 512, 5000, 64, 64, 30, 3, 50, 1048576),
                            containers,
                            new InteractiveSandboxMetrics(meters),
                            watchdog,
                            meters));

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
    void shouldInterruptStalledWritesWithOrWithoutTheServerRole(boolean serverEnabled) {
        var interrupted = new AtomicBoolean();
        watchdog.register(UUID.randomUUID(), stalledTarget(interrupted));
        runner.withPropertyValues("hephaestus.runtime.server.enabled=" + serverEnabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                                    .flatMap(holder -> holder.getScheduledTasks().stream()))
                            .hasSize(3);
                    await().atMost(Duration.ofSeconds(3)).untilTrue(interrupted);
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

    @Test
    void shouldKeepTheWatchdogRunningWhileDockerReconciliationBlocks() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(containers.listManagedContainers()).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return java.util.List.of();
        });
        runner.withPropertyValues("hephaestus.sandbox.reconciliation-initial-delay-seconds=0")
                .run(context -> {
                    try {
                        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                        var interrupted = new AtomicBoolean();
                        watchdog.register(UUID.randomUUID(), stalledTarget(interrupted));
                        await().atMost(Duration.ofSeconds(3)).untilTrue(interrupted);
                    } finally {
                        release.countDown();
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"specs", "cds-training"})
    void shouldNotAccessDockerOrTheDatabaseInBuildOnlyProfiles(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledTaskRegistrar.class);
            context.publishEvent(new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO));
            verifyNoInteractions(jobs, containers, networks);
        });
    }

    @Test
    void shouldNotScheduleMaintenanceWithoutTheWorkerRole() {
        runner.withPropertyValues("hephaestus.runtime.worker.enabled=false").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ScheduledTaskRegistrar.class);
            verifyNoInteractions(jobs, containers, networks);
        });
    }

    @Test
    void shouldCancelMaintenanceWhenTheContextCloses() {
        var tasks = new java.util.ArrayList<org.springframework.scheduling.config.ScheduledTask>();
        runner.run(context -> context.getBeansOfType(ScheduledTaskRegistrar.class)
                .values()
                .forEach(registrar -> tasks.addAll(registrar.getScheduledTasks())));
        assertThat(tasks)
                .hasSize(3)
                .allSatisfy(task -> assertThat(task.nextExecution()).isNull());
    }

    private static StdinWriteWatchdog.StallTarget stalledTarget(AtomicBoolean interrupted) {
        return new StdinWriteWatchdog.StallTarget() {
            @Override
            public boolean writeStalled(long nowNanos) {
                return !interrupted.get();
            }

            @Override
            public void onWriteTimeout() {
                interrupted.set(true);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({SandboxProperties.class, InteractiveSandboxProperties.class})
    static class PropertiesConfiguration {}
}
