package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class SandboxContainerManagerTest extends BaseUnitTest {

    @Mock
    private DockerContainerOperations containerOps;

    private SandboxContainerManager manager;
    private SandboxProperties properties;
    private ExecutorService executor;

    private static final String CONTAINER_ID = "abc123";

    @BeforeEach
    void setUp() {
        properties = new SandboxProperties(5, 10, 60, 209_715_200L, 500_000, null);
        executor = Executors.newSingleThreadExecutor();
        manager = new SandboxContainerManager(containerOps, image -> {}, properties, executor, Duration.ofMillis(20));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Nested
    class CreateContainer {

        @Test
        void shouldEnsureImageIsPresentWhenCreatingContainer() {
            List<String> guarded = new ArrayList<>();
            SandboxContainerManager guardedManager =
                    new SandboxContainerManager(containerOps, guarded::add, properties, executor);
            DockerOperations.ContainerSpec spec = new DockerOperations.ContainerSpec(
                    "ghcr.io/example/agent:latest",
                    List.of("true"),
                    Map.of(),
                    null,
                    null,
                    null,
                    Map.of(),
                    mock(DockerOperations.HostConfigSpec.class),
                    List.of());
            when(containerOps.createContainer(spec)).thenReturn(CONTAINER_ID);

            guardedManager.createContainer(spec);

            assertThat(guarded).containsExactly("ghcr.io/example/agent:latest");
        }
    }

    @Nested
    class WaitForCompletion {

        @Test
        void shouldReturnExitCodeOnCompletion() {
            when(containerOps.waitContainer(CONTAINER_ID)).thenReturn(new DockerOperations.WaitResult(0));

            SandboxContainerManager.WaitOutcome outcome =
                    manager.waitForCompletion(CONTAINER_ID, Duration.ofMinutes(5));

            assertThat(outcome.exitCode()).isZero();
            assertThat(outcome.timedOut()).isFalse();
        }

        @Test
        void shouldReturnNonZeroExitCode() {
            when(containerOps.waitContainer(CONTAINER_ID)).thenReturn(new DockerOperations.WaitResult(42));

            SandboxContainerManager.WaitOutcome outcome =
                    manager.waitForCompletion(CONTAINER_ID, Duration.ofMinutes(5));

            assertThat(outcome.exitCode()).isEqualTo(42);
            assertThat(outcome.timedOut()).isFalse();
        }

        @Test
        void shouldTimeoutAndStop() {
            // Simulate a container that never exits
            when(containerOps.waitContainer(CONTAINER_ID)).thenAnswer(inv -> {
                new CountDownLatch(1).await();
                return new DockerOperations.WaitResult(137);
            });

            SandboxContainerManager.WaitOutcome outcome =
                    manager.waitForCompletion(CONTAINER_ID, Duration.ofMillis(100));

            assertThat(outcome.timedOut()).isTrue();
            verify(containerOps).stopContainer(eq(CONTAINER_ID), anyInt());
        }

        @Test
        void shouldThrowOnExecutionError() {
            when(containerOps.waitContainer(CONTAINER_ID)).thenThrow(new SandboxException("Docker error"));

            assertThatThrownBy(() -> manager.waitForCompletion(CONTAINER_ID, Duration.ofMinutes(5)))
                    .isInstanceOf(SandboxException.class);
        }

        @Test
        void shouldReInterruptOnInitialWaitInterruption() throws Exception {
            // Block waitContainer so we can interrupt the calling thread
            when(containerOps.waitContainer(CONTAINER_ID)).thenAnswer(inv -> {
                Thread.sleep(10_000);
                return new DockerOperations.WaitResult(0);
            });

            Thread testThread = Thread.currentThread();
            // Schedule interruption after a short delay
            var interruptor = new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
                testThread.interrupt();
            });
            interruptor.start();

            assertThatThrownBy(() -> manager.waitForCompletion(CONTAINER_ID, Duration.ofMinutes(5)))
                    .isInstanceOf(SandboxException.class)
                    .hasMessageContaining("Interrupted");

            // Thread interrupt flag should be restored
            assertThat(Thread.interrupted()).isTrue(); // also clears it
            interruptor.join(1000);
        }

        @Test
        void shouldHandlePostTimeoutWaitFailure() {
            // waitContainer blocks forever, triggering timeout, then post-stop wait also fails
            when(containerOps.waitContainer(CONTAINER_ID)).thenAnswer(inv -> {
                new CountDownLatch(1).await();
                return new DockerOperations.WaitResult(0);
            });

            SandboxContainerManager.WaitOutcome outcome =
                    manager.waitForCompletion(CONTAINER_ID, Duration.ofMillis(50));

            // Should return SIGKILL exit code because post-stop wait times out
            assertThat(outcome.timedOut()).isTrue();
            assertThat(outcome.exitCode()).isEqualTo(137);
        }

        @Test
        void shouldTolerateStopFailureDuringTimeout() {
            when(containerOps.waitContainer(CONTAINER_ID)).thenAnswer(inv -> {
                new CountDownLatch(1).await();
                return new DockerOperations.WaitResult(0);
            });
            doThrow(new SandboxException("stop failed")).when(containerOps).stopContainer(eq(CONTAINER_ID), anyInt());

            SandboxContainerManager.WaitOutcome outcome =
                    manager.waitForCompletion(CONTAINER_ID, Duration.ofMillis(50));

            // Should still return timeout result even if stop failed
            assertThat(outcome.timedOut()).isTrue();
        }
    }

    @Nested
    class GetLogs {

        @Test
        void shouldReturnLogs() {
            when(containerOps.getLogs(CONTAINER_ID, 100)).thenReturn("test output\n");

            String logs = manager.getLogs(CONTAINER_ID, 100);

            assertThat(logs).isEqualTo("test output\n");
        }

        @Test
        void shouldReturnEmptyOnFailure() {
            when(containerOps.getLogs(CONTAINER_ID, 100)).thenThrow(new RuntimeException("Docker error"));

            String logs = manager.getLogs(CONTAINER_ID, 100);

            assertThat(logs).isEmpty();
        }
    }

    @Nested
    class ForceRemove {

        @Test
        void shouldDelegateForceRemove() {
            manager.forceRemove(CONTAINER_ID);

            verify(containerOps).removeContainer(CONTAINER_ID, true);
        }
    }

    @Nested
    class ListManagedContainers {

        @Test
        void shouldListByManagedLabel() {
            when(containerOps.listContainersByLabel("hephaestus.managed", "true"))
                    .thenReturn(List.of(
                            new DockerOperations.ContainerInfo("c1", "/test", Map.of(), "running", Instant.EPOCH)));

            var containers = manager.listManagedContainers();

            assertThat(containers).hasSize(1);
            assertThat(containers.get(0).id()).isEqualTo("c1");
        }
    }
}
