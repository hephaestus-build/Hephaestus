package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

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

    private static final String CANARY = "sk-canary-7f3a9c";

    /** What an older runtime image wrote: free text after the prefix, which can quote a tool or a provider. */
    private static final String OLD_RUNTIME_LOG = String.join(
            "\n",
            "[pi-runner] review practice-a tool error: bash — cat: .env: API_KEY=" + CANARY,
            "[pi-runner] review practice-a provider call failed, retrying in 2000ms (attempt 1/3): 401 key " + CANARY,
            "[pi-runner] result refused for practice-a: the quote \"" + CANARY + "\" is not in src/Auth.java",
            "println from a reviewed program " + CANARY,
            "[heph-host 2026-10-26T20:11:30Z] " + CANARY,
            "[pi-runner] tool.failed turn=review:practice-a tool=bash reason_chars=42",
            "[pi-runner] review.partial without_result=1 practices=2 not_reached=practice-a");

    @Test
    void shouldKeepOnlyLinesOfTheDiagnosticGrammarAndCountTheRest() {
        String log = IntStream.rangeClosed(1, 30)
                        .mapToObj(i -> "[pi-runner] tool.called turn=review:practice-a tool=read n=" + i)
                        .collect(Collectors.joining("\n"))
                + "\n" + OLD_RUNTIME_LOG + "\n[pi-runner] turn.finished turn=review:practice-a stored="
                + "1".repeat(90) + " calls=" + "2".repeat(90) + " stopped_by=" + "3".repeat(90) + " refused=0";

        SandboxExits.Tail tail = SandboxExits.stderrTail(log);

        List<String> lines = tail.text().lines().toList();
        assertThat(lines).hasSize(SandboxExits.TAIL_LINES_KEPT);
        assertThat(lines).allMatch(line -> line.startsWith("[pi-runner] "));
        assertThat(tail.text()).doesNotContain(CANARY).doesNotContain("reviewed program");
        assertThat(lines.getLast()).hasSize(SandboxExits.TAIL_LINE_CHARS);
        assertThat(tail.text().length()).isLessThanOrEqualTo(SandboxExits.TAIL_CHARS);
        assertThat(tail.dropped()).isEqualTo(5);
    }

    @Test
    void shouldNeverWriteAToolResultOrAProviderMessageOfAnOlderRuntimeIntoTheLog() {
        var pods = mock(KubernetesPods.class);
        when(pods.get("hephaestus-review-2")).thenReturn(pod(null, ended(KubernetesPods.RUNTIME, 1, "Error")));
        when(pods.tail(anyString(), anyString(), anyInt(), anyInt())).thenReturn(OLD_RUNTIME_LOG);
        Logger logger = (Logger) LoggerFactory.getLogger(SandboxExits.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new SandboxExits(pods, new SimpleMeterRegistry()).record(SandboxExits.Kind.REVIEW, "hephaestus-review-2");
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getFormattedMessage()).doesNotContain(CANARY);
        assertThat(event.getKeyValuePairs())
                .allSatisfy(pair -> assertThat(String.valueOf(pair.value)).doesNotContain(CANARY));
        assertThat(event.getKeyValuePairs())
                .anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("sandbox.stderr.dropped");
                    assertThat(pair.value).isEqualTo(5);
                })
                .anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("sandbox.stderr.tail");
                    assertThat(String.valueOf(pair.value))
                            .contains("tool.failed")
                            .contains("review.partial");
                });
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
