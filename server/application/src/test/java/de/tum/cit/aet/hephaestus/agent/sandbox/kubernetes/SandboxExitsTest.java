package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SandboxExitsTest extends BaseUnitTest {

    @Test
    void shouldNameTheContainerThatRanOutOfMemoryWithItsExitCode() {
        var exit = SandboxExits.exit(pod(
                null,
                ended(KubernetesPods.INITIALIZER, 0, "Completed"),
                ended(KubernetesPods.RUNTIME, 137, "OOMKilled")));

        assertThat(exit).isEqualTo(new SandboxExits.Exit(SandboxExits.Reason.OOM_KILLED, "runtime", 137, "OOMKilled"));
    }

    @Test
    void shouldTellAnErrorAnEvictionAStartThatFailedAndACompletedRun() {
        assertThat(SandboxExits.exit(pod(null, ended(KubernetesPods.RUNTIME, 1, "Error")))
                        .reason())
                .isEqualTo(SandboxExits.Reason.ERROR);
        assertThat(SandboxExits.exit(pod("Evicted")).reason()).isEqualTo(SandboxExits.Reason.EVICTED);
        assertThat(SandboxExits.exit(pod(
                                null,
                                new ContainerStatusBuilder()
                                        .withName(KubernetesPods.RUNTIME)
                                        .withNewState()
                                        .withNewWaiting()
                                        .withReason("ImagePullBackOff")
                                        .endWaiting()
                                        .endState()
                                        .build()))
                        .reason())
                .isEqualTo(SandboxExits.Reason.START_FAILED);
        assertThat(SandboxExits.exit(pod(null, ended(KubernetesPods.RUNTIME, 0, "Completed")))
                        .reason())
                .isEqualTo(SandboxExits.Reason.COMPLETED);
    }

    @Test
    void shouldNotCallAPodThatNeverRanOrFailedWithoutACauseCompleted() {
        assertThat(SandboxExits.exit(phase("Pending")).reason()).isEqualTo(SandboxExits.Reason.START_FAILED);
        assertThat(SandboxExits.exit(phase("Failed")).reason()).isEqualTo(SandboxExits.Reason.UNKNOWN);
        assertThat(SandboxExits.exit(phase("Running")).reason()).isEqualTo(SandboxExits.Reason.COMPLETED);
    }

    @Test
    void shouldCountAnUnknownEndAndGoOnWhenThePodCannotBeRead() {
        var meters = new SimpleMeterRegistry();
        var pods = mock(KubernetesPods.class);
        when(pods.get("hephaestus-review-1")).thenThrow(new IllegalStateException("the API went away"));

        new SandboxExits(pods, meters).record(SandboxExits.Kind.REVIEW, "hephaestus-review-1");

        assertThat(meters.get(AgentMetrics.SANDBOX_EXITS)
                        .tag("kind", "review")
                        .tag("reason", "unknown")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void shouldNeverReadTheOutputOfAHephHostThatFailed() {
        var pods = mock(KubernetesPods.class);
        when(pods.get("hephaestus-host-1")).thenReturn(pod(null, ended(KubernetesPods.RUNTIME, 1, "Error")));

        new SandboxExits(pods, new SimpleMeterRegistry()).record(SandboxExits.Kind.HOST, "hephaestus-host-1");

        verify(pods, never()).tail(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void shouldKeepOnlyTheRuntimesOwnLastLinesOfStderr() {
        String log = IntStream.rangeClosed(1, 30)
                        .mapToObj(i -> "[heph-host 2026-10-26T20:11:3" + (i % 10) + "Z] line " + i)
                        .collect(Collectors.joining("\n"))
                + "\nprintln from a reviewed program\n[pi-runner] the last runtime line " + "x".repeat(400);

        String tail = SandboxExits.stderrTail(log);

        List<String> lines = tail.lines().toList();
        assertThat(lines).hasSize(SandboxExits.TAIL_LINES_KEPT);
        assertThat(lines).noneMatch(line -> line.contains("reviewed program"));
        assertThat(lines.getLast())
                .startsWith("[pi-runner] the last runtime line")
                .hasSize(SandboxExits.TAIL_LINE_CHARS);
        assertThat(tail.length()).isLessThanOrEqualTo(SandboxExits.TAIL_CHARS);
    }

    @Test
    void shouldCountEveryKindAndReasonFromZeroAndAPodThatIsGoneAsNotFound() {
        var meters = new SimpleMeterRegistry();
        var pods = mock(KubernetesPods.class);
        when(pods.get("hephaestus-host-gone")).thenReturn(null);
        var exits = new SandboxExits(pods, meters);

        assertThat(meters.find(AgentMetrics.SANDBOX_EXITS).counters())
                .hasSize(SandboxExits.Kind.values().length * SandboxExits.Reason.values().length);

        exits.record(SandboxExits.Kind.HOST, "hephaestus-host-gone");

        assertThat(meters.get(AgentMetrics.SANDBOX_EXITS)
                        .tag("kind", "host")
                        .tag("reason", "not_found")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    private static Pod pod(@Nullable String reason, ContainerStatus... containers) {
        return new PodBuilder()
                .withNewMetadata()
                .withName("hephaestus-host-1")
                .endMetadata()
                .withNewStatus()
                .withPhase(reason == null ? "Succeeded" : "Failed")
                .withReason(reason)
                .withContainerStatuses(containers)
                .endStatus()
                .build();
    }

    private static Pod pod(String reason) {
        return pod(reason, new ContainerStatus[0]);
    }

    private static Pod phase(String phase) {
        return new PodBuilder()
                .withNewMetadata()
                .withName("hephaestus-host-1")
                .endMetadata()
                .withNewStatus()
                .withPhase(phase)
                .endStatus()
                .build();
    }

    private static ContainerStatus ended(String container, int code, String reason) {
        return new ContainerStatusBuilder()
                .withName(container)
                .withNewState()
                .withNewTerminated()
                .withExitCode(code)
                .withReason(reason)
                .endTerminated()
                .endState()
                .build();
    }
}
