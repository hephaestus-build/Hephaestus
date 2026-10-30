package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PiToolTimingMetricsTest extends BaseUnitTest {
    @Test
    void shouldRetainCompleteDurationsAndCountCensoredSpans() {
        var meters = new SimpleMeterRegistry();
        var mapper = new ObjectMapper();
        var parser = new PiResultParser(mapper, meters);
        parser.recordToolTimings(mapper.readTree("""
                {"spans":[{"durationMs":1.5,"outcome":"completed"},{"durationMs":20,"outcome":"completed"}],"unfinished":2,"omitted":3}
                """));
        var timer = meters.get(AgentMetrics.AGENT_PHASE_REPORTED_DURATION)
                .tags("workload", "review", "phase", "tool_call")
                .timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(21.5);
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_TOOL_TIMING_CENSORED)
                        .tag("reason", "unfinished")
                        .counter()
                        .count())
                .isEqualTo(2);
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_TOOL_TIMING_CENSORED)
                        .tag("reason", "omitted")
                        .counter()
                        .count())
                .isEqualTo(3);
    }

    @Test
    void shouldRejectTheWholeMalformedReportWithoutBiasedPartialSamples() {
        var meters = new SimpleMeterRegistry();
        var mapper = new ObjectMapper();
        var parser = new PiResultParser(mapper, meters);
        parser.recordToolTimings(mapper.readTree("""
                {"spans":[{"durationMs":10,"outcome":"completed"},{"durationMs":"bad","outcome":"completed"}],"unfinished":0,"omitted":0}
                """));
        assertThat(meters.find(AgentMetrics.AGENT_PHASE_REPORTED_DURATION).timer())
                .isNull();
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_TOOL_TIMING_INVALID)
                        .counter()
                        .count())
                .isEqualTo(1);
        parser.recordToolTimings(mapper.createObjectNode().path("missing"));
        assertThat(meters.get(AgentMetrics.AGENT_PHASE_TOOL_TIMING_UNAVAILABLE)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldConsumeToolTelemetryWithoutPersistingSamplesOrChangingThePrimaryResult() {
        var meters = new SimpleMeterRegistry();
        var mapper = new ObjectMapper();
        var parser = new PiResultParser(mapper, meters);
        Gauge.builder(AgentMetrics.AGENT_PHASE_TOOL_TIMING_CENSORED, () -> 1)
                .tag("reason", "unfinished")
                .register(meters);
        var files = Map.of(
                "result.json", "{\"results\":[]}".getBytes(StandardCharsets.UTF_8),
                "runner-debug.json",
                        "{\"label\":\"retained\",\"toolTimings\":{\"spans\":[{\"durationMs\":2,\"outcome\":\"completed\"}],\"unfinished\":1,\"omitted\":0}}"
                                .getBytes(StandardCharsets.UTF_8));
        var result = parser.parse(new SandboxResult(0, files, "", false, Duration.ZERO));
        assertThat(result.success()).isTrue();
        assertThat(result.output().get("runnerDebug")).isEqualTo(Map.of("label", "retained"));
    }
}
