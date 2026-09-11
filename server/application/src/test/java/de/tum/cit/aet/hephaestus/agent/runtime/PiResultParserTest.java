package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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

    @Test
    void missingResultFile() {
        var result = parser.parse(new SandboxResult(0, Map.of(), "done", false, Duration.ofSeconds(10)));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).doesNotContainKey("rawOutput");
    }

    @Test
    void failureBecomesFailure() {
        var result = parser.parse(new SandboxResult(1, Map.of(), "x", false, Duration.ofSeconds(5)));
        assertThat(result.success()).isFalse();
    }

    @Test
    void rebuildsFromReviewState() {
        String reviewState = """
            {"observations":[{"practiceSlug":"x","title":"t","assessmentStatus": "ASSESSED", "presence": "ABSENT","assessment":"BAD","severity":"MAJOR",
            "evidence":{"citations":[]},"reasoning":"r"}]}""";
        var result = parser.parse(new SandboxResult(
                1,
                Map.of("review-state.json", reviewState.getBytes(StandardCharsets.UTF_8)),
                "runner failed",
                false,
                Duration.ofSeconds(10)));
        String raw = rawOutput(result).toString();
        assertThat(raw).contains("\"x\"").contains("\"ABSENT\"");
    }

    @Test
    void extractsJsonFromMixedText() {
        String mixed = "Here:\n```json\n{\"observations\":[{\"practiceSlug\":\"t\",\"title\":\"a\","
                + "\"presence\":\"ABSENT\",\"assessment\":\"BAD\",\"severity\":\"MAJOR\",\"confidence\":0.8}]}\n```";
        var result = parser.parse(
                new SandboxResult(0, Map.of("result.json", mixed.getBytes()), "done", false, Duration.ofSeconds(10)));
        assertThat(rawOutput(result).toString()).contains("observations").contains("ABSENT");
    }

    @Test
    void surfacesUsageAndRunnerDebug() {
        String observations =
                "{\"observations\":[{\"practiceSlug\":\"t\",\"title\":\"x\",\"presence\":\"PRESENT\",\"assessment\":\"GOOD\","
                        + "\"severity\":\"INFO\",\"confidence\":0.9}]}";
        String usage = "{\"model\":\"m\",\"inputTokens\":10,\"outputTokens\":5,\"cacheReadTokens\":20,"
                + "\"costUsd\":0.12,\"totalCalls\":2}";
        String debug = "{\"attempts\":[],\"usageTotals\":{\"totalCalls\":2}}";
        var result = parser.parse(new SandboxResult(
                0,
                Map.of(
                        "result.json",
                        observations.getBytes(),
                        "usage.json",
                        usage.getBytes(),
                        "runner-debug.json",
                        debug.getBytes()),
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

        assertThat(result.output()).containsKey("practiceCoverage");
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
            Map<String, Object> output = new java.util.HashMap<>();
            parser.addPracticeCoverage(output, coverage.getBytes(StandardCharsets.UTF_8));
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
    void sanitizesSwiftEscapes() {
        String json = "{\"observations\":[{\"practiceSlug\":\"t\",\"title\":\"line1\\nline2\","
                + "\"presence\":\"PRESENT\",\"assessment\":\"GOOD\",\"severity\":\"INFO\",\"confidence\":0.9,"
                + "\"reasoning\":\"Text(\\\"\\(weather.temp)°\\\")\"}]}";
        var result = parser.parse(
                new SandboxResult(0, Map.of("result.json", json.getBytes()), "done", false, Duration.ofSeconds(10)));
        assertThat(result.success()).isTrue();
        assertThat(rawOutput(result).toString()).contains("line1\\nline2");
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
    void emptyReviewStateNoOutput() {
        String empty = "{\"observations\":[]}";
        var result = parser.parse(new SandboxResult(
                1,
                Map.of("review-state.json", empty.getBytes(StandardCharsets.UTF_8)),
                "failed",
                false,
                Duration.ofSeconds(10)));
        assertThat(result.output()).doesNotContainKey("rawOutput");
    }

    @Test
    void zeroCallsUsageIgnored() {
        String observations = "{\"observations\":[]}";
        String usage = "{\"model\":\"m\",\"totalCalls\":0}";
        var result = parser.parse(new SandboxResult(
                0,
                Map.of("result.json", observations.getBytes(), "usage.json", usage.getBytes()),
                "done",
                false,
                Duration.ofSeconds(10)));
        assertThat(result.usage()).isNull();
    }
}
