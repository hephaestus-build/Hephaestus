package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The drain is the worker's only shutdown setting: every shutdown phase waits for it, whatever
 * {@code spring.lifecycle.timeout-per-shutdown-phase} says. A phase timeout below the drain would let Spring close
 * the database under a review that is still draining.
 */
class WorkerShutdownTimeoutTest extends BaseUnitTest {

    private static final long DRAIN_MS = 300;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LifecycleAutoConfiguration.class))
            .withUserConfiguration(WorkerPropertiesConfiguration.class, Phases.class)
            .withPropertyValues(
                    RuntimeRole.WORKER_PROPERTY + "=true",
                    "hephaestus.worker.drain.timeout=" + DRAIN_MS + "ms",
                    "spring.lifecycle.timeout-per-shutdown-phase=50ms");

    @Test
    void shouldWaitForTheWholeDrainWhenTheConfiguredPhaseTimeoutIsShorter() {
        AtomicBoolean drainedBeforeLaterPhases = new AtomicBoolean();

        runner.run(context -> {
            Phases phases = context.getBean(Phases.class);
            context.close();
            drainedBeforeLaterPhases.set(phases.drainFinishedWhenLaterPhaseStopped.get());
        });

        assertThat(drainedBeforeLaterPhases).isTrue();
    }

    /** A drain at the coordinator's phase that takes the whole drain timeout, and a later phase that records it. */
    @Configuration(proxyBeanMethods = false)
    static class Phases {

        final AtomicBoolean drainFinished = new AtomicBoolean();
        final AtomicBoolean drainFinishedWhenLaterPhaseStopped = new AtomicBoolean();

        @Bean
        SmartLifecycle drain() {
            return new Recording(WorkerDrainCoordinator.PHASE) {
                @Override
                public void stop(Runnable callback) {
                    stop();
                    Thread.ofVirtual().start(() -> {
                        try {
                            Thread.sleep(DRAIN_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        drainFinished.set(true);
                        callback.run();
                    });
                }
            };
        }

        @Bean
        SmartLifecycle laterPhase() {
            return new Recording(WorkerDrainCoordinator.PHASE - 1) {
                @Override
                public void stop() {
                    drainFinishedWhenLaterPhaseStopped.set(drainFinished.get());
                    super.stop();
                }
            };
        }
    }

    private static class Recording implements SmartLifecycle {

        private final int phase;
        private volatile boolean running;

        Recording(int phase) {
            this.phase = phase;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }
}
