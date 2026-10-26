package de.tum.cit.aet.hephaestus.agent.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PiResultParserTest extends BaseUnitTest {

    private static Object rawOutput(AgentResult result) {
        Object output = result.output().get("rawOutput");
        assertThat(output).isNotNull();
        return output;
    }

    private PiResultParser parser;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        parser = new PiResultParser(new ObjectMapper(), meterRegistry);
    }

    @Test
    @DisplayName("emits agent.pi.result.parse.failure{stage=usage} when usage.json is invalid")
    void emitsParseFailureMetric() {
        var bad = "not-json".getBytes(StandardCharsets.UTF_8);
        parser.parseUsage(bad);
        assertThat(meterRegistry
                        .counter("agent.pi.result.parse.failure", "stage", "usage")
                        .count())
                .isEqualTo(1d);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{\"results\":[]}",
                "{\"summary\":\"no results\"}",
                "Saved:\n{\"results\":[{\"practiceSlug\":\"x\"}]}",
                "{\"results\":[{\"practiceSlug\":\"x\"}]}\nDone.",
                "{\"results\":[{\"summary\":\"\\(error)\"}]}"
            })
    @DisplayName("a run with no result and no usable review state is not a success, even when it exits cleanly")
    void noUsableResultIsNotASuccess(String reviewState) {
        Map<String, byte[]> files =
                reviewState.isEmpty() ? Map.of() : Map.of("review-state.json", reviewState.getBytes(UTF_8));

        var result = parser.parse(new SandboxResult(0, files, "done", false, Duration.ofSeconds(10)));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).doesNotContainKey("rawOutput");
    }

    @Test
    void failureBecomesFailure() {
        var result = parser.parse(new SandboxResult(1, Map.of(), "x", false, Duration.ofSeconds(5)));
        assertThat(result.success()).isFalse();
    }

    @Test
    void shouldAssembleTheAdmissionBodyWhenOnlyTheReviewStateIsLeft() {
        String reviewState = """
            {"contractVersion":3,"results":[{"practiceSlug":"x","candidate":{"outcome":"MET","summary":"t",\
            "explanation":"r","citations":[]}}]}\
            """;
        var result = parser.parse(new SandboxResult(
                1,
                Map.of("review-state.json", reviewState.getBytes(StandardCharsets.UTF_8)),
                "runner failed",
                false,
                Duration.ofSeconds(10)));

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode assembled = mapper.readTree(rawOutput(result).toString());
        assertThat(result.success()).isFalse();
        assertThat(assembled.path("contractVersion").asInt()).isEqualTo(SandboxLayout.PRACTICE_CONTRACT_VERSION);
        assertThat(assembled.path("results"))
                .isEqualTo(mapper.readTree(reviewState).path("results"));
    }

    @Test
    @DisplayName("keeps the runner's serialized result exactly, and its results read back verbatim")
    void keepsNativeJsonExactly() {
        // As JSON.stringify writes it: escaped quotes, a backslash pair before a Swift interpolation, a newline.
        String nativeJson = """
            {"contractVersion":3,"results":[{"practiceSlug":"silent-failure","candidate":{"outcome":"NOT_MET",\
            "summary":"Catch says \\"ok\\"","explanation":"print(\\"Error: \\\\(error)\\")\\nC:\\\\temp",\
            "citations":[],"problems":[{"severity":"MAJOR","summary":"s","explanation":"e",\
            "citations":[{"path":"App/Weather.swift","quote":"Text(\\"\\\\(weather.temp)°\\")"}]}]}}]}\
            """;

        var result = parser.parse(new SandboxResult(
                0, Map.of("result.json", nativeJson.getBytes(UTF_8)), "done", false, Duration.ofSeconds(10)));

        assertThat(result.success()).isTrue();
        assertThat(rawOutput(result)).isEqualTo(nativeJson);
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode candidate = mapper.readTree(rawOutput(result).toString())
                .path("results")
                .path(0)
                .path("candidate");
        assertThat(candidate.path("summary").asString()).isEqualTo("Catch says \"ok\"");
        assertThat(candidate.path("explanation").asString()).isEqualTo("print(\"Error: \\(error)\")\nC:\\temp");
        JsonNode citation = candidate.path("problems").path(0).path("citations").path(0);
        assertThat(citation.path("quote").asString()).isEqualTo("Text(\"\\(weather.temp)°\")");
    }

    @Test
    void shouldAcceptAnEmptySubmissionWhenAContinuationWritesNoReport() {
        String empty = "{\"contractVersion\":3,\"results\":[]}";

        var result = parser.parse(new SandboxResult(
                0, Map.of("result.json", empty.getBytes(UTF_8)), "done", false, Duration.ofSeconds(10)));

        assertThat(result.success()).isTrue();
        assertThat(rawOutput(result)).isEqualTo(empty);
    }

    @Test
    @DisplayName("a malformed result is not rescued by the backup, and the run's other artifacts are kept")
    void malformedResultKeepsOtherArtifacts() {
        String reviewState = """
            {"contractVersion":3,"results":[{"practiceSlug":"x","candidate":{"outcome":"MET","summary":"t",
            "explanation":"r","citations":[]}}]}\
            """;
        var result = parser.parse(new SandboxResult(
                0,
                Map.of(
                        "result.json",
                        "Result:\n{\"results\":[]}".getBytes(UTF_8),
                        "review-state.json",
                        reviewState.getBytes(UTF_8),
                        "usage.json",
                        "{\"model\":\"m\",\"totalCalls\":1}".getBytes(UTF_8)),
                "done",
                false,
                Duration.ofSeconds(10)));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).doesNotContainKeys("rawOutput", "feedback");
        assertThat(result.usage()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Here:\n{\"results\":[]}",
                "```json\n{\"results\":[]}\n```",
                "{\"results\":[]}\nDone.",
                "{\"results\":[{\"summary\":\"\\(error)\"}]}",
                "{\"summary\":\"no results\"}",
                "[]"
            })
    @DisplayName("refuses a result that is not one serialized object with results, and repairs nothing")
    void refusesMalformedResult(String malformed) {
        var result = parser.parse(new SandboxResult(
                0, Map.of("result.json", malformed.getBytes(UTF_8)), "done", false, Duration.ofSeconds(10)));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).doesNotContainKey("rawOutput");
        assertThat(meterRegistry
                        .counter("agent.pi.result.parse.failure", "stage", "result")
                        .count())
                .isEqualTo(1d);
    }

    @Test
    void surfacesUsageAndRunnerDebug() {
        String practiceResults =
                "{\"contractVersion\":3,\"results\":[{\"practiceSlug\":\"t\",\"candidate\":{\"outcome\":\"MET\","
                        + "\"summary\":\"x\",\"explanation\":\"y\",\"citations\":[]}}]}";
        String usage = "{\"model\":\"m\",\"inputTokens\":10,\"outputTokens\":5,\"cacheReadTokens\":20,"
                + "\"costUsd\":0.12,\"totalCalls\":2}";
        String debug = "{\"attempts\":[],\"usageTotals\":{\"totalCalls\":2}}";
        var result = parser.parse(new SandboxResult(
                0,
                Map.of(
                        "result.json",
                        practiceResults.getBytes(UTF_8),
                        "usage.json",
                        usage.getBytes(UTF_8),
                        "runner-debug.json",
                        debug.getBytes(UTF_8)),
                "done",
                false,
                Duration.ofSeconds(10)));
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().model()).isEqualTo("m");
        assertThat(result.usage().totalCalls()).isEqualTo(2);
        assertThat(result.usage().inputTokens()).isEqualTo(10);
        assertThat(result.usage().costUsd()).isEqualTo(0.12);
        assertThat(result.usage().reasoningTokens()).isNull();
        assertThat(result.output()).containsKey("runnerDebug");
    }

    @Test
    void surfacesPerRunPracticeCoverageAndRecordsItsRatio() {
        String coverage = """
            {"eligible":4,"evaluated":2,"outcomes":[
              {"practiceSlug":"a","outcome":"EVALUATED"},
              {"practiceSlug":"b","outcome":"NOT_REACHED"},
              {"practiceSlug":"c","outcome":"EVALUATED"},
              {"practiceSlug":"d","outcome":"NOT_REACHED"}]}
            """;

        var result = parser.parse(new SandboxResult(
                1,
                Map.of("practice-coverage.json", coverage.getBytes(StandardCharsets.UTF_8)),
                "budget exhausted",
                false,
                Duration.ofSeconds(10)));

        assertThat(result.output()).doesNotContainKey("practiceCoverage");
        assertThat(meterRegistry
                        .get("agent.review.practice.coverage.eligible")
                        .summary()
                        .totalAmount())
                .isEqualTo(4);
        assertThat(meterRegistry
                        .get("agent.review.practice.coverage.evaluated")
                        .summary()
                        .totalAmount())
                .isEqualTo(2);
        assertThat(meterRegistry
                        .get("agent.review.practice.coverage.ratio")
                        .summary()
                        .totalAmount())
                .isEqualTo(0.5);
    }

    @Test
    void rejectsIncompleteOrContradictoryPracticeCoverage() {
        String[] invalid = {
            "{\"eligible\":2,\"evaluated\":1,\"outcomes\":[]}",
            "{\"eligible\":2,\"evaluated\":1,\"outcomes\":[{\"practiceSlug\":\"a\",\"outcome\":\"EVALUATED\"},{\"practiceSlug\":\"a\",\"outcome\":\"NOT_REACHED\"}]}",
            "{\"eligible\":1,\"evaluated\":1,\"outcomes\":[{\"practiceSlug\":\"a\",\"outcome\":\"UNKNOWN\"}]}"
        };

        for (String coverage : invalid) {
            Map<String, Object> output = new HashMap<>();
            parser.addPracticeCoverage(coverage.getBytes(StandardCharsets.UTF_8));
            assertThat(output).doesNotContainKey("practiceCoverage");
        }
        assertThat(meterRegistry
                        .counter("agent.pi.result.parse.failure", "stage", "practice_coverage")
                        .count())
                .isEqualTo(invalid.length);
    }

    @Test
    @DisplayName("reasoningTokens is populated from the responses-path shape when the runner reports it")
    void populatesReasoningTokensWhenPresent() {
        String usage = "{\"model\":\"gpt-5.4\",\"inputTokens\":100,\"outputTokens\":50,\"reasoningTokens\":30,"
                + "\"totalCalls\":1}";
        var result = parser.parseUsage(usage.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        assertThat(result.reasoningTokens()).isEqualTo(30);
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(50);
    }

    @Test
    @DisplayName("reasoningTokens stays null for a model that never reports it (chat/completions-only)")
    void reasoningTokensNullWhenAbsent() {
        String usage = "{\"model\":\"gpt-oss-120b\",\"inputTokens\":100,\"outputTokens\":50,\"totalCalls\":1}";
        var result = parser.parseUsage(usage.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isNotNull();
        assertThat(result.reasoningTokens()).isNull();
    }

    @Test
    @DisplayName("watchdog-killed marker is surfaced into output")
    void surfacesWatchdogState() {
        String marker = "{\"budgetMs\":540000,\"elapsedMs\":570000,\"reason\":\"x\"}";
        var result = parser.parse(new SandboxResult(
                3,
                Map.of("watchdog-killed.json", marker.getBytes(StandardCharsets.UTF_8)),
                "killed",
                false,
                Duration.ofSeconds(570)));
        assertThat(result.output()).containsKey("watchdogKilled");
    }

    @Test
    void zeroCallsUsageIgnored() {
        String practiceResults = "{\"results\":[]}";
        String usage = "{\"model\":\"m\",\"totalCalls\":0}";
        var result = parser.parse(new SandboxResult(
                0,
                Map.of("result.json", practiceResults.getBytes(UTF_8), "usage.json", usage.getBytes(UTF_8)),
                "done",
                false,
                Duration.ofSeconds(10)));
        assertThat(result.usage()).isNull();
    }
}
