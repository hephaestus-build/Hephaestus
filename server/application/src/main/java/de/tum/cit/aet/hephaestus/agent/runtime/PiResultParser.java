package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelPurpose;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeModelUseDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNeed;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNotRatedDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeNotRatedReason;
import de.tum.cit.aet.hephaestus.practices.spi.PrecomputeRunStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * artifact costs only that artifact. The precompute report is read apart from the result, by
 * {@link #parsePrecomputeReport}, because only the job knows which scripts it staged.
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

    /**
     * One staged precompute script's run, as {@link SandboxLayout#PRECOMPUTE_REPORT_FILE} reported it.
     *
     * @param models the models the script declared; empty for a script that uses no model, and {@code null} when
     *     they are not known because the script ended before it declared them or did not finish
     * @param error the first line of the script's error, at most {@link #PRECOMPUTE_ERROR_MAX_LENGTH} characters;
     *     {@code null} unless the script failed and the runner named the error
     * @param durationMs how long the script ran; {@code null} when it did not run, did not finish, or the runner did not
     *     say
     */
    public record PrecomputeRunReport(
            String practiceSlug,
            PrecomputeRunStatus status,
            int leads,
            @Nullable List<PrecomputeModelUseDTO> models,
            @Nullable String error,
            @Nullable Integer durationMs) {
        public PrecomputeRunReport {
            models = models == null ? null : List.copyOf(models);
        }

        /** A staged script the report does not name: the runner lists every staged script, finished or not. */
        static PrecomputeRunReport notFinished(String practiceSlug) {
            return new PrecomputeRunReport(practiceSlug, PrecomputeRunStatus.NOT_FINISHED, 0, null, null, null);
        }
    }

    static final int PRECOMPUTE_REPORT_MAX_BYTES = 64 * 1024;
    static final int PRECOMPUTE_REPORT_MAX_ENTRIES = 256;

    /** The longest error line that a precompute run keeps, in characters, as {@code precompute.json} bounds it. */
    public static final int PRECOMPUTE_ERROR_MAX_LENGTH = 500;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern BLANK_START = Pattern.compile("^[\\p{Cc}\\p{Z}]+");
    private static final Pattern BLANK_END = Pattern.compile("[\\p{Cc}\\p{Z}]+\\z");
    private static final Pattern LINE_END = Pattern.compile("[\\r\\n]");

    private static final Map<String, PrecomputeRunStatus> PRECOMPUTE_STATUSES = Map.of(
            "ok", PrecomputeRunStatus.OK,
            "skipped", PrecomputeRunStatus.SKIPPED,
            "error", PrecomputeRunStatus.FAILED,
            "timeout", PrecomputeRunStatus.TIMED_OUT,
            "not-finished", PrecomputeRunStatus.NOT_FINISHED);

    private static final Map<String, PrecomputeNeed> PRECOMPUTE_NEEDS =
            Map.of("required", PrecomputeNeed.REQUIRED, "optional", PrecomputeNeed.OPTIONAL);

    private static final Map<String, PrecomputeNotRatedReason> NOT_RATED_REASONS = Arrays.stream(
                    PrecomputeNotRatedReason.values())
            .collect(Collectors.toUnmodifiableMap(PrecomputeNotRatedReason::wire, reason -> reason));

    /**
     * Each purpose by the slot of its agent purpose's model kind. Built from the mirror, so an agent purpose that
     * the mirror lacks leaves its slot unknown here instead of failing to load; a sync test names that drift.
     */
    private static final Map<String, PrecomputeModelPurpose> PURPOSES_BY_SLOT = Arrays.stream(
                    PrecomputeModelPurpose.values())
            .collect(Collectors.toUnmodifiableMap(
                    purpose -> AgentPurpose.valueOf(purpose.name()).kind().slot(), purpose -> purpose));

    /**
     * The precompute runs of one attempt, one per staged script. An entry with a value outside the contract,
     * a duplicate slug, or a slug the job did not stage is dropped and counted. A staged script that a whole
     * report does not name did not finish. A truncated report says nothing about the scripts it does not name,
     * so they get no run.
     *
     * <p>A missing or unreadable report yields no runs at all. It says nothing about any script, and recording
     * "did not finish" for each would turn a lost file into a claim about the scripts.
     *
     * @param staged the slugs of the practices whose precompute script the job staged
     */
    public List<PrecomputeRunReport> parsePrecomputeReport(byte @Nullable [] reportFile, Set<String> staged) {
        if (reportFile == null || reportFile.length == 0) {
            return List.of();
        }
        if (reportFile.length > PRECOMPUTE_REPORT_MAX_BYTES) {
            recordFailure("precompute_report", new IllegalArgumentException("report too large"));
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper
                    .readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(reportFile);
        } catch (JacksonException e) {
            recordFailure("precompute_report", e);
            return List.of();
        }
        JsonNode entries = root != null && root.isObject() ? root.get("practices") : null;
        JsonNode truncated = root != null && root.isObject() ? root.get("truncated") : null;
        if (entries == null
                || !entries.isArray()
                || entries.size() > PRECOMPUTE_REPORT_MAX_ENTRIES
                || truncated == null
                || !truncated.isBoolean()) {
            recordFailure(
                    "precompute_report", new IllegalArgumentException("no bounded practices array or truncation flag"));
            return List.of();
        }
        Set<String> named = new HashSet<>();
        Map<String, PrecomputeRunReport> runs = new TreeMap<>();
        for (JsonNode entry : entries) {
            String slug = entry.path("slug").isString() ? entry.path("slug").asString() : null;
            if (slug == null || !staged.contains(slug)) {
                recordFailure("precompute_report", new IllegalArgumentException("entry for no staged script"));
                continue;
            }
            boolean first = named.add(slug);
            PrecomputeRunReport run = precomputeRun(slug, entry);
            if (run == null || !first) {
                // The runner did name the script, so it gets no run rather than one the runner did not report.
                recordFailure("precompute_report", new IllegalArgumentException("invalid or duplicate entry"));
                runs.remove(slug);
                continue;
            }
            runs.put(slug, run);
        }
        if (!truncated.asBoolean()) {
            for (String slug : staged) {
                if (!named.contains(slug)) runs.put(slug, PrecomputeRunReport.notFinished(slug));
            }
        }
        return List.copyOf(runs.values());
    }

    private static @Nullable PrecomputeRunReport precomputeRun(String slug, JsonNode entry) {
        PrecomputeRunStatus status =
                PRECOMPUTE_STATUSES.get(entry.path("status").asString(""));
        Integer leads = count(entry.path("leads"));
        JsonNode declared = entry.path("models");
        List<PrecomputeModelUseDTO> models = declared.isMissingNode() ? null : modelUses(declared);
        if (status == null || leads == null || (!declared.isMissingNode() && models == null)) {
            return null;
        }
        // A script that did not run found nothing, and a report that says otherwise is not believed.
        boolean ran = status != PrecomputeRunStatus.SKIPPED && status != PrecomputeRunStatus.NOT_FINISHED;
        if (!ran && leads != 0) {
            return null;
        }
        // A script that did not finish declared nothing.
        if (status == PrecomputeRunStatus.NOT_FINISHED && models != null) {
            return null;
        }
        String error = status == PrecomputeRunStatus.FAILED ? errorLine(entry.path("error")) : null;
        // A script that did not run took no time. A duration only explains the run, so a missing or malformed one
        // leaves the run without it.
        Integer durationMs = ran ? count(entry.path("durationMs")) : null;
        return new PrecomputeRunReport(slug, status, leads, models, error, durationMs);
    }

    /**
     * The line of a failed script's error that the run keeps, by the rule of {@code errorLine} in
     * {@code pi-precompute-report.ts}: the first line after blanks, cut to {@link #PRECOMPUTE_ERROR_MAX_LENGTH} code
     * points, with each control character as a space and no blanks at the end. A line that is still not
     * {@link StorableText} is left out: the run is written in the attempt's terminal transaction, which a refused
     * value would fail. The error only explains the failure, so a missing or malformed one leaves the run without it
     * instead of dropping the run. {@code precompute-error-lines.json} holds the cases that both sides agree on.
     */
    private static @Nullable String errorLine(JsonNode error) {
        if (!error.isString()) return null;
        String text = BLANK_START.matcher(error.asString()).replaceFirst("");
        Matcher lineEnd = LINE_END.matcher(text);
        String first = lineEnd.find() ? text.substring(0, lineEnd.start()) : text;
        String cut = first.codePointCount(0, first.length()) <= PRECOMPUTE_ERROR_MAX_LENGTH
                ? first
                : first.substring(0, first.offsetByCodePoints(0, PRECOMPUTE_ERROR_MAX_LENGTH));
        String line = BLANK_END.matcher(CONTROL.matcher(cut).replaceAll(" ")).replaceFirst("");
        return line.isEmpty() || !StorableText.isStorable(line) ? null : line;
    }

    private static @Nullable List<PrecomputeModelUseDTO> modelUses(JsonNode models) {
        if (!models.isArray()) return null;
        List<PrecomputeModelUseDTO> uses = new ArrayList<>();
        Set<PrecomputeModelPurpose> seen = new HashSet<>();
        for (JsonNode model : models) {
            PrecomputeModelPurpose purpose =
                    PURPOSES_BY_SLOT.get(model.path("slot").asString(""));
            PrecomputeNeed need = PRECOMPUTE_NEEDS.get(model.path("need").asString(""));
            List<PrecomputeNotRatedDTO> notRated = notRated(model.path("notRated"));
            if (purpose == null
                    || !seen.add(purpose)
                    || need == null
                    || !model.path("bound").isBoolean()
                    || notRated == null) {
                return null;
            }
            uses.add(
                    new PrecomputeModelUseDTO(purpose, need, model.path("bound").asBoolean(), notRated));
        }
        return uses;
    }

    /** By reason, in the declared order of the reasons; a zero count is no fact and is left out. */
    private static @Nullable List<PrecomputeNotRatedDTO> notRated(JsonNode counts) {
        if (counts.isMissingNode()) return List.of();
        if (!counts.isObject()) return null;
        Map<PrecomputeNotRatedReason, Integer> byReason = new EnumMap<>(PrecomputeNotRatedReason.class);
        for (Map.Entry<String, JsonNode> property : counts.properties()) {
            PrecomputeNotRatedReason reason = NOT_RATED_REASONS.get(property.getKey());
            Integer count = count(property.getValue());
            if (reason == null || count == null) return null;
            if (count > 0) byReason.put(reason, count);
        }
        return byReason.entrySet().stream()
                .map(entry -> new PrecomputeNotRatedDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** A non-negative int, or null for anything else. */
    private static @Nullable Integer count(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 ? value.asInt() : null;
    }

    /** Logs the failure's type only: a parser message can quote the runner's output. */
    private void recordFailure(String stage, Exception e) {
        log.warn("Failed to parse Pi {} output: {}", stage, e.getClass().getSimpleName());
        meterRegistry
                .counter(AgentMetrics.AGENT_PI_RESULT_PARSE_FAILURE, Tags.of("stage", stage))
                .increment();
    }
}
