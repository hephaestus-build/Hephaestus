package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parse Pi-runner output into an {@link AgentResult}.
 *
 * <p>Falls back to persisted review state when the primary result is absent and surfaces auxiliary
 * runner artifacts. A malformed result is not a successful run and carries no {@code rawOutput}.
 *
 * <p>Parse failures are counted by the {@code agent.pi.result.parse.failure{stage}} counter; a malformed auxiliary
 * artifact costs only that artifact.
 */
@Service
public class PiResultParser {

    private static final Logger log = LoggerFactory.getLogger(PiResultParser.class);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final DistributionSummary eligiblePractices;
    private final DistributionSummary evaluatedPractices;
    private final DistributionSummary practiceCoverageRatio;

    public PiResultParser(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.eligiblePractices = DistributionSummary.builder(AgentMetrics.AGENT_REVIEW_PRACTICE_COVERAGE_ELIGIBLE)
                .description("Eligible practices per review run.")
                .register(meterRegistry);
        this.evaluatedPractices = DistributionSummary.builder(AgentMetrics.AGENT_REVIEW_PRACTICE_COVERAGE_EVALUATED)
                .description("Evaluated practices per review run.")
                .register(meterRegistry);
        this.practiceCoverageRatio = DistributionSummary.builder(AgentMetrics.AGENT_REVIEW_PRACTICE_COVERAGE_RATIO)
                .description("Fraction of eligible practices evaluated per review run.")
                .maximumExpectedValue(1.0)
                .register(meterRegistry);
    }

    /**
     * Parse the sandbox result, falling back to {@code review-state.json} when {@code result.json} is absent. A run
     * with no readable result is not a success, whatever its exit code.
     */
    public AgentResult parse(SandboxResult sandboxResult) {
        boolean success = sandboxResult.exitCode() == 0 && !sandboxResult.timedOut();
        Map<String, Object> output = new HashMap<>();
        output.put("exitCode", sandboxResult.exitCode());
        output.put("timedOut", sandboxResult.timedOut());
        addWatchdogState(output, sandboxResult.outputFiles().get("watchdog-killed.json"));
        AgentResult.LlmUsage usage = parseUsage(sandboxResult.outputFiles().get("usage.json"));
        addRunnerDebug(output, sandboxResult.outputFiles().get("runner-debug.json"));
        addPracticeCoverage(output, sandboxResult.outputFiles().get("practice-coverage.json"));
        // Before the result-file branches below, which early-return: the composition stage's output is
        // independent of whether the review's own observations parsed, and losing it because the observations
        // were malformed would silently couple two things that must not be coupled.
        addComposedFeedback(output, sandboxResult.outputFiles().get(SandboxLayout.FEEDBACK_FILENAME));

        // A malformed primary result is the run's answer; the review-state backup stands in only for a missing one.
        byte[] resultFile = sandboxResult.outputFiles().get("result.json");
        if (resultFile == null) {
            resultFile = buildResultFromReviewState(sandboxResult.outputFiles().get("review-state.json"));
        } else if (observationsOf(resultFile, "result") == null) {
            resultFile = null;
        }
        if (resultFile == null) {
            return new AgentResult(false, output, usage);
        }
        output.put("rawOutput", new String(resultFile, StandardCharsets.UTF_8));
        return new AgentResult(success, output, usage);
    }

    /**
     * The runner writes its result files with {@code JSON.stringify}: one JSON object and nothing around it. Anything
     * else is refused, never repaired or extracted from text.
     */
    private @Nullable JsonNode observationsOf(byte[] file, String stage) {
        try {
            JsonNode root = objectMapper
                    .readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(file);
            JsonNode observations = root != null && root.isObject() ? root.get("observations") : null;
            if (observations != null && observations.isArray()) {
                return observations;
            }
            recordFailure(stage, new IllegalArgumentException("no observations array"));
        } catch (JacksonException e) {
            recordFailure(stage, e);
        }
        return null;
    }

    void addPracticeCoverage(Map<String, Object> output, byte @Nullable [] coverageFile) {
        if (coverageFile == null || coverageFile.length == 0) return;
        try {
            JsonNode coverage = objectMapper.readTree(coverageFile);
            int eligible = coverage.path("eligible").asInt(-1);
            int evaluated = coverage.path("evaluated").asInt(-1);
            JsonNode outcomes = coverage.path("outcomes");
            if (!validCoverage(eligible, evaluated, outcomes)) {
                throw new IllegalArgumentException("invalid practice coverage");
            }
            output.put("practiceCoverage", objectMapper.treeToValue(coverage, Object.class));
            eligiblePractices.record(eligible);
            evaluatedPractices.record(evaluated);
            if (eligible > 0) practiceCoverageRatio.record((double) evaluated / eligible);
            log.info(
                    "Practice review coverage: evaluated={}, eligible={}, ratio={}",
                    evaluated,
                    eligible,
                    eligible == 0 ? "n/a" : (double) evaluated / eligible);
        } catch (JacksonException | IllegalArgumentException e) {
            recordFailure("practice_coverage", e);
        }
    }

    private static boolean validCoverage(int eligible, int evaluated, JsonNode outcomes) {
        if (eligible < 0
                || evaluated < 0
                || evaluated > eligible
                || !outcomes.isArray()
                || outcomes.size() != eligible) {
            return false;
        }
        Set<String> slugs = new HashSet<>();
        int evaluatedOutcomes = 0;
        for (JsonNode outcome : outcomes) {
            String slug = outcome.path("practiceSlug").asString(null);
            String value = outcome.path("outcome").asString();
            if (slug == null || slug.isBlank() || !slugs.add(slug)) return false;
            if ("EVALUATED".equals(value)) evaluatedOutcomes++;
            else if (!"NOT_REACHED".equals(value)) return false;
        }
        return evaluatedOutcomes == evaluated;
    }

    AgentResult.@Nullable LlmUsage parseUsage(byte @Nullable [] usageFile) {
        if (usageFile == null || usageFile.length == 0) {
            return null;
        }
        try {
            JsonNode usageNode = objectMapper.readTree(usageFile);
            int totalCalls = usageNode.path("totalCalls").asInt(0);
            if (totalCalls <= 0) {
                return null;
            }
            String model =
                    usageNode.path("model").isString() ? usageNode.path("model").asString() : null;
            Integer inputTokens =
                    usageNode.has("inputTokens") ? usageNode.path("inputTokens").asInt(0) : null;
            Integer outputTokens = usageNode.has("outputTokens")
                    ? usageNode.path("outputTokens").asInt(0)
                    : null;
            Integer cacheReadTokens = usageNode.has("cacheReadTokens")
                    ? usageNode.path("cacheReadTokens").asInt(0)
                    : null;
            Integer cacheWriteTokens = usageNode.has("cacheWriteTokens")
                    ? usageNode.path("cacheWriteTokens").asInt(0)
                    : null;
            // Populated from the responses-path shape's `output_tokens_details.reasoning_tokens` when the
            // upstream model reports it; absent for chat/completions-only models.
            Integer reasoningTokens = usageNode.has("reasoningTokens")
                    ? usageNode.path("reasoningTokens").asInt(0)
                    : null;
            Double costUsd =
                    usageNode.has("costUsd") ? usageNode.path("costUsd").asDouble(0.0) : null;
            return new AgentResult.LlmUsage(
                    model,
                    inputTokens,
                    outputTokens,
                    reasoningTokens,
                    cacheReadTokens,
                    cacheWriteTokens,
                    costUsd,
                    totalCalls);
        } catch (JacksonException e) {
            recordFailure("usage", e);
            return null;
        }
    }

    void addRunnerDebug(Map<String, Object> output, byte @Nullable [] runnerDebugFile) {
        if (runnerDebugFile == null || runnerDebugFile.length == 0) {
            return;
        }
        try {
            output.put("runnerDebug", objectMapper.readValue(runnerDebugFile, Object.class));
        } catch (JacksonException e) {
            recordFailure("runner_debug", e);
        }
    }

    /**
     * Surfaces the feedback-composition stage's payload under {@code feedback}, for each lane's producer to
     * read off the job. Best-effort like its siblings: a malformed payload costs the surfaces one cycle's
     * messages and costs the review nothing.
     */
    void addComposedFeedback(Map<String, Object> output, byte @Nullable [] feedbackFile) {
        if (feedbackFile == null || feedbackFile.length == 0) {
            return;
        }
        try {
            output.put("feedback", objectMapper.readValue(feedbackFile, Object.class));
        } catch (JacksonException e) {
            recordFailure("composed_feedback", e);
        }
    }

    void addWatchdogState(Map<String, Object> output, byte @Nullable [] watchdogFile) {
        if (watchdogFile == null || watchdogFile.length == 0) {
            return;
        }
        try {
            output.put("watchdogKilled", objectMapper.readValue(watchdogFile, Object.class));
        } catch (JacksonException e) {
            recordFailure("watchdog", e);
        }
    }

    byte @Nullable [] buildResultFromReviewState(byte @Nullable [] reviewStateFile) {
        if (reviewStateFile == null || reviewStateFile.length == 0) {
            return null;
        }
        JsonNode observations = observationsOf(reviewStateFile, "review_state");
        if (observations == null || observations.isEmpty()) {
            return null;
        }
        Map<String, Object> assembled = new LinkedHashMap<>();
        assembled.put("observations", observations);
        return objectMapper.writeValueAsBytes(assembled);
    }

    /** Logs the failure's type only: a parser message can quote the runner's output. */
    private void recordFailure(String stage, Exception e) {
        log.warn("Failed to parse Pi {} output: {}", stage, e.getClass().getSimpleName());
        meterRegistry
                .counter(AgentMetrics.AGENT_PI_RESULT_PARSE_FAILURE, Tags.of("stage", stage))
                .increment();
    }
}
