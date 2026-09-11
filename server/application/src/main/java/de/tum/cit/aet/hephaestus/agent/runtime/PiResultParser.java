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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parse Pi-runner output into an {@link AgentResult}.
 *
 * <p>Falls back to persisted review state when the primary result is absent and surfaces auxiliary
 * runner artifacts. Sanitises Swift {@code \(...)} interpolation that produces invalid JSON.
 *
 * <p>Parse failures are non-fatal (best-effort) and counted by the
 * {@code agent.pi.result.parse.failure{stage}} counter.
 */
@Service
public class PiResultParser {

    private static final Logger log = LoggerFactory.getLogger(PiResultParser.class);
    private static final int MAX_BRACE_ATTEMPTS = 5;

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

    /** Parse the sandbox result, falling back to {@code review-state.json} when {@code result.json} is absent. */
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

        byte[] resultFile = sandboxResult.outputFiles().get("result.json");
        if (resultFile == null) {
            resultFile = buildResultFromReviewState(sandboxResult.outputFiles().get("review-state.json"));
        }
        if (resultFile == null) {
            return new AgentResult(success, output, usage);
        }

        String rawContent = sanitizeSwiftEscapes(new String(resultFile, StandardCharsets.UTF_8));
        if (isValidJsonWithObservations(rawContent)) {
            output.put("rawOutput", rawContent);
            return new AgentResult(success, output, usage);
        }

        String extracted = extractJsonFromText(rawContent);
        output.put("rawOutput", extracted != null ? extracted : rawContent);
        return new AgentResult(success, output, usage);
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
        try {
            JsonNode root = objectMapper.readTree(reviewStateFile);
            JsonNode observations = root.get("observations");
            if (observations == null || !observations.isArray() || observations.isEmpty()) {
                return null;
            }
            Map<String, Object> assembled = new LinkedHashMap<>();
            assembled.put("observations", objectMapper.treeToValue(observations, Object.class));
            return objectMapper.writeValueAsBytes(assembled);
        } catch (JacksonException e) {
            recordFailure("review_state", e);
            return null;
        }
    }

    /** Drop invalid JSON escapes produced when the LLM quotes Swift string interpolation. */
    String sanitizeSwiftEscapes(String json) {
        if (json == null || !json.contains("\\")) {
            return json;
        }
        StringBuilder sb = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"'
                        || next == '\\'
                        || next == '/'
                        || next == 'b'
                        || next == 'f'
                        || next == 'n'
                        || next == 'r'
                        || next == 't'
                        || next == 'u') {
                    sb.append(c);
                } else {
                    continue;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Find the first '{'…'}' object containing an observations array (max {@value MAX_BRACE_ATTEMPTS} attempts). */
    @Nullable
    String extractJsonFromText(String text) {
        int searchFrom = 0;
        char[] chars = text.toCharArray();
        int attempts = 0;
        while (searchFrom < chars.length && attempts < MAX_BRACE_ATTEMPTS) {
            int bracePos = text.indexOf('{', searchFrom);
            if (bracePos == -1) {
                break;
            }
            attempts++;
            try (var parser = objectMapper.createParser(chars, bracePos, chars.length - bracePos)) {
                JsonNode node = objectMapper.readTree(parser);
                if (node != null && node.isObject() && observationsNode(node) != null) {
                    return objectMapper.writeValueAsString(node);
                }
            } catch (JacksonException e) {
                log.trace("No JSON object at position {}: {}", bracePos, e.getMessage());
            }
            searchFrom = bracePos + 1;
        }
        return null;
    }

    boolean isValidJsonWithObservations(String text) {
        try {
            JsonNode node = objectMapper.readTree(text);
            return node != null && node.isObject() && observationsNode(node) != null;
        } catch (JacksonException e) {
            return false;
        }
    }

    private static @Nullable JsonNode observationsNode(JsonNode root) {
        JsonNode observations = root.get("observations");
        return observations != null && observations.isArray() ? observations : null;
    }

    private void recordFailure(String stage, Exception e) {
        log.warn("Failed to parse Pi {} output: {}", stage, e.getMessage());
        meterRegistry
                .counter(AgentMetrics.AGENT_PI_RESULT_PARSE_FAILURE, Tags.of("stage", stage))
                .increment();
    }
}
