package de.tum.cit.aet.hephaestus.agent.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations.Outcome;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations.Phase;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentPhaseObservations.Workload;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.spi.ModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.Need;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptKind;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptRunStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ScriptStopReason;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.ObjectNode;

/** Malformed primary output cannot establish success; malformed auxiliary output drops only that auxiliary data. */
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
        addPracticeCoverage(sandboxResult.outputFiles().get("practice-coverage.json"));
        // A malformed primary result is the run's answer; the review-state backup stands in only for a missing one.
        byte[] resultFile = sandboxResult.outputFiles().get("result.json");
        if (resultFile == null) {
            resultFile = buildResultFromReviewState(sandboxResult.outputFiles().get("review-state.json"));
        } else if (practiceResultsOf(resultFile, "result") == null) {
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
    private @Nullable JsonNode practiceResultsOf(byte[] file, String stage) {
        try {
            JsonNode root = objectMapper
                    .readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(file);
            JsonNode practiceResults = root != null && root.isObject() ? root.get("results") : null;
            if (practiceResults != null && practiceResults.isArray()) {
                return practiceResults;
            }
            recordFailure(stage, new IllegalArgumentException("no results array"));
        } catch (JacksonException e) {
            recordFailure(stage, e);
        }
        return null;
    }

    void addPracticeCoverage(byte @Nullable [] coverageFile) {
        if (coverageFile == null || coverageFile.length == 0) return;
        try {
            JsonNode coverage = objectMapper.readTree(coverageFile);
            int eligible = coverage.path("eligible").asInt(-1);
            int evaluated = coverage.path("evaluated").asInt(-1);
            JsonNode outcomes = coverage.path("outcomes");
            if (!validCoverage(eligible, evaluated, outcomes)) {
                throw new IllegalArgumentException("invalid practice coverage");
            }
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
            UsageFile usage = objectMapper
                    .readerFor(UsageFile.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(usageFile);
            if (usage.totalCalls() <= 0) return null;
            return new AgentResult.LlmUsage(
                    usage.model(),
                    usage.inputTokens(),
                    usage.outputTokens(),
                    usage.reasoningTokens(),
                    usage.cacheReadTokens(),
                    usage.cacheWriteTokens(),
                    usage.costUsd(),
                    usage.totalCalls());
        } catch (JacksonException | IllegalArgumentException e) {
            recordFailure("usage", e);
            return null;
        }
    }

    private record UsageFile(
            @Nullable String model,
            @Nullable Integer inputTokens,
            @Nullable Integer outputTokens,
            @Nullable Integer reasoningTokens,
            @Nullable Integer cacheReadTokens,
            @Nullable Integer cacheWriteTokens,
            @Nullable Double costUsd,
            int totalCalls) {
        @JsonCreator
        UsageFile {
            for (Integer tokens :
                    Arrays.asList(inputTokens, outputTokens, reasoningTokens, cacheReadTokens, cacheWriteTokens))
                if (tokens != null && tokens < 0) throw new IllegalArgumentException("Usage cannot be negative");
            if (costUsd != null && (!Double.isFinite(costUsd) || costUsd < 0))
                throw new IllegalArgumentException("Usage cost must be finite and non-negative");
            if (totalCalls < 0) throw new IllegalArgumentException("Usage calls cannot be negative");
        }
    }

    void addRunnerDebug(Map<String, Object> output, byte @Nullable [] runnerDebugFile) {
        if (runnerDebugFile == null || runnerDebugFile.length == 0) {
            return;
        }
        try {
            JsonNode debug = objectMapper.readTree(runnerDebugFile);
            if (debug == null) return;
            JsonNode timings = debug.path("toolTimings");
            if (debug instanceof ObjectNode object) object.remove("toolTimings");
            output.put("runnerDebug", objectMapper.convertValue(debug, Object.class));
            try {
                recordToolTimings(timings);
            } catch (RuntimeException exception) {
                log.warn(
                        "Could not record review tool timing: {}",
                        exception.getClass().getSimpleName());
            }
        } catch (JacksonException e) {
            recordFailure("runner_debug", e);
        }
    }

    /** Only complete SDK tool spans become samples; missing or bounded-out spans remain visible. */
    void recordToolTimings(JsonNode timings) {
        if (timings.isMissingNode()) {
            meterRegistry
                    .counter(AgentMetrics.AGENT_PHASE_TOOL_TIMING_UNAVAILABLE)
                    .increment();
            return;
        }
        JsonNode durations = timings.path("spans");
        JsonNode unfinished = timings.path("unfinished");
        JsonNode omitted = timings.path("omitted");
        boolean valid = durations.isArray()
                && durations.size() <= 4096
                && validToolCount(unfinished)
                && validToolCount(omitted);
        if (valid) {
            for (JsonNode duration : durations) {
                double millis = duration.path("durationMs").asDouble(Double.NaN);
                if (!duration.path("durationMs").isNumber()
                        || !Double.isFinite(millis)
                        || millis < 0
                        || millis > 86_400_000
                        || !(duration.path("outcome").asString("").equals("completed")
                                || duration.path("outcome").asString("").equals("failed"))) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            meterRegistry.counter(AgentMetrics.AGENT_PHASE_TOOL_TIMING_INVALID).increment();
            return;
        }
        for (JsonNode duration : durations) {
            AgentPhaseObservations.record(
                    meterRegistry,
                    Workload.REVIEW,
                    Phase.TOOL_CALL,
                    Duration.ofNanos(Math.round(duration.path("durationMs").asDouble() * 1_000_000d)),
                    duration.path("outcome").asString().equals("completed") ? Outcome.COMPLETED : Outcome.FAILED);
        }
        meterRegistry
                .counter(AgentMetrics.AGENT_PHASE_TOOL_TIMING_CENSORED, "reason", "unfinished")
                .increment((double) unfinished.longValue());
        meterRegistry
                .counter(AgentMetrics.AGENT_PHASE_TOOL_TIMING_CENSORED, "reason", "omitted")
                .increment((double) omitted.longValue());
    }

    private static boolean validToolCount(JsonNode count) {
        return count.isIntegralNumber()
                && count.canConvertToLong()
                && count.longValue() >= 0
                && count.longValue() <= 1_000_000;
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
        JsonNode practiceResults = practiceResultsOf(reviewStateFile, "review_state");
        if (practiceResults == null || practiceResults.isEmpty()) {
            return null;
        }
        Map<String, Object> assembled = new LinkedHashMap<>();
        assembled.put("contractVersion", SandboxLayout.PRACTICE_CONTRACT_VERSION);
        assembled.put("results", practiceResults);
        return objectMapper.writeValueAsBytes(assembled);
    }

    /**
     * One admitted practice's program run, as {@link SandboxLayout#PRACTICE_SCRIPT_REPORT_FILE} reported it.
     *
     * @param programDigest the SHA-256 of the staged script as the runner reported it; null for the built-in
     * @param stopReason why the program stopped without a candidate; null exactly when it returned one, or did not
     *     finish
     * @param models the direct models the program declared; empty for the built-in and for a script that declares
     *     none, and {@code null} when they are not known because the program did not finish
     * @param calls the calls the program made, {@code {review, chat, decision, embedding, reranking}}; null when the
     *     practice is not named by the report
     * @param coverageReached the reads the program's host counted; null when not named by the report
     * @param reviewSessions what the practice's review sessions did as the runner counted it,
     *     {@code {modelCalls, evidenceRefusals, formatRefusals}}; null when the report does not say. These are the
     *     runner's own counts, not the calls that the proxy billed.
     * @param error the first line of the program's error, at most {@link #PRACTICE_SCRIPT_ERROR_MAX_LENGTH}
     *     characters; {@code null} unless it failed and the runner named the error
     * @param durationMs how long the program ran; {@code null} when it did not start or did not finish, or the runner
     *     did not say
     */
    public record PracticeScriptRunReport(
            String practiceSlug,
            ScriptRunStatus status,
            ScriptKind programKind,
            @Nullable String programDigest,
            @Nullable ScriptStopReason stopReason,
            @Nullable List<ScriptModelUse> models,
            @Nullable Map<String, Integer> calls,
            @Nullable Map<String, Integer> coverageReached,
            @Nullable Map<String, Integer> reviewSessions,
            @Nullable String error,
            @Nullable Integer durationMs) {
        public PracticeScriptRunReport {
            models = models == null ? null : List.copyOf(models);
            calls = calls == null ? null : Map.copyOf(calls);
            coverageReached = coverageReached == null ? null : Map.copyOf(coverageReached);
            reviewSessions = reviewSessions == null ? null : Map.copyOf(reviewSessions);
        }

        /** An admitted practice the report does not name: the runner lists every admitted practice, finished or not. */
        static PracticeScriptRunReport notFinished(String practiceSlug, ScriptKind programKind) {
            return new PracticeScriptRunReport(
                    practiceSlug,
                    ScriptRunStatus.NOT_FINISHED,
                    programKind,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    static final int PRACTICE_SCRIPT_REPORT_VERSION = 2;

    static final int PRACTICE_SCRIPT_REPORT_MAX_BYTES = 64 * 1024;
    static final int PRACTICE_SCRIPT_REPORT_MAX_ENTRIES = 256;

    public static final int PRACTICE_SCRIPT_ERROR_MAX_LENGTH = 500;

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    private static final Pattern BLANK_START = Pattern.compile("^[\\p{Cc}\\p{Z}]+");
    private static final Pattern BLANK_END = Pattern.compile("[\\p{Cc}\\p{Z}]+\\z");
    private static final Pattern LINE_END = Pattern.compile("[\\r\\n]");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    private static final Map<String, ScriptRunStatus> PRACTICE_SCRIPT_STATUSES = Map.of(
            "ok", ScriptRunStatus.OK,
            "withheld", ScriptRunStatus.WITHHELD,
            "not-finished", ScriptRunStatus.NOT_FINISHED,
            "failed", ScriptRunStatus.FAILED,
            "not-reached", ScriptRunStatus.NOT_REACHED);

    private static final Map<String, ScriptKind> PROGRAM_KINDS =
            Map.of("builtin", ScriptKind.BUILTIN, "custom", ScriptKind.CUSTOM);

    private static final Map<String, ScriptStopReason> STOP_REASONS = Arrays.stream(ScriptStopReason.values())
            .collect(Collectors.toUnmodifiableMap(
                    reason -> reason == ScriptStopReason.INVALID_SCRIPT
                            ? "invalid-program"
                            : reason.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                    reason -> reason));

    public static final List<String> CALL_KINDS = List.of("review", "chat", "decision", "embedding", "reranking");

    private static final Set<String> MODEL_FAILURE_REASONS =
            Set.of("unavailable", "budget", "deadline", "too-large", "off-format", "refused", "error");

    private static final List<String> COVERAGE_COUNTS = List.of("reads", "truncatedReads", "failedReads");

    private static final List<String> REVIEW_SESSION_COUNTS =
            List.of("modelCalls", "evidenceRefusals", "formatRefusals");

    private static final Set<String> ENTRY_KEYS = Set.of(
            "practiceSlug",
            "program",
            "models",
            "status",
            "stopReason",
            "error",
            "calls",
            "coverageReached",
            "reviewSessions",
            "durationMs");

    private static final Map<String, Need> PRACTICE_SCRIPT_NEEDS =
            Map.of("required", Need.REQUIRED, "optional", Need.OPTIONAL);

    private static final Map<String, ModelSlot> PURPOSES_BY_SLOT =
            Arrays.stream(ModelSlot.values()).collect(Collectors.toUnmodifiableMap(ModelSlot::wireValue, slot -> slot));

    /**
     * The program runs of one attempt, one per admitted practice. An entry with a value outside the contract, a
     * duplicate slug, or a slug the job did not admit is dropped and counted. An admitted practice that a whole
     * report does not name did not finish. A truncated report says nothing about the practices it does not name, so
     * they get no run.
     *
     * <p>A missing or unreadable report yields no runs at all. It says nothing about any program, and recording "did
     * not finish" for each would turn a lost file into a claim about the programs. An invalid report never creates a
     * verdict: it only explains execution.
     *
     * @param admitted the program kind of each admitted practice, by slug: custom when its commit holds a script
     */
    public List<PracticeScriptRunReport> parsePracticeScriptReport(
            byte @Nullable [] reportFile, Map<String, ScriptKind> admitted) {
        if (reportFile == null || reportFile.length == 0) {
            return List.of();
        }
        if (reportFile.length > PRACTICE_SCRIPT_REPORT_MAX_BYTES) {
            recordFailure("practice_script_report", new IllegalArgumentException("report too large"));
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper
                    .readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(reportFile);
        } catch (JacksonException e) {
            recordFailure("practice_script_report", e);
            return List.of();
        }
        JsonNode entries = root != null && root.isObject() ? root.get("practices") : null;
        JsonNode truncated = root != null && root.isObject() ? root.get("truncated") : null;
        JsonNode version = root != null && root.isObject() ? root.get("schemaVersion") : null;
        if (version == null
                || !version.isIntegralNumber()
                || version.asInt() != PRACTICE_SCRIPT_REPORT_VERSION
                || entries == null
                || !entries.isArray()
                || entries.size() > PRACTICE_SCRIPT_REPORT_MAX_ENTRIES
                || truncated == null
                || !truncated.isBoolean()) {
            recordFailure(
                    "practice_script_report",
                    new IllegalArgumentException(
                            "no report schema version 2, bounded practices array or truncation flag"));
            return List.of();
        }
        Set<String> named = new HashSet<>();
        Map<String, PracticeScriptRunReport> runs = new TreeMap<>();
        for (JsonNode entry : entries) {
            String slug = entry.path("practiceSlug").isString()
                    ? entry.path("practiceSlug").asString()
                    : null;
            if (slug == null || !admitted.containsKey(slug)) {
                recordFailure("practice_script_report", new IllegalArgumentException("entry for no admitted practice"));
                continue;
            }
            boolean first = named.add(slug);
            PracticeScriptRunReport run = practiceScriptRun(slug, entry, admitted.get(slug));
            if (run == null || !first) {
                // The runner did name the practice, so it gets no run rather than one the runner did not report.
                recordFailure("practice_script_report", new IllegalArgumentException("invalid or duplicate entry"));
                runs.remove(slug);
                continue;
            }
            runs.put(slug, run);
        }
        if (!truncated.asBoolean()) {
            admitted.forEach((slug, kind) -> {
                if (!named.contains(slug)) runs.put(slug, PracticeScriptRunReport.notFinished(slug, kind));
            });
        }
        return List.copyOf(runs.values());
    }

    private static @Nullable PracticeScriptRunReport practiceScriptRun(
            String slug, JsonNode entry, @Nullable ScriptKind admittedKind) {
        if (!entry.isObject() || !ENTRY_KEYS.containsAll(entry.propertyNames())) return null;
        ScriptRunStatus status =
                PRACTICE_SCRIPT_STATUSES.get(entry.path("status").asString(""));
        JsonNode program = entry.path("program");
        ScriptKind kind = PROGRAM_KINDS.get(program.path("kind").asString(""));
        JsonNode digestNode = program.path("digest");
        String digest =
                digestNode.isString() && DIGEST.matcher(digestNode.asString()).matches() ? digestNode.asString() : null;
        if (status == null
                || kind == null
                || kind != admittedKind
                || !program.isObject()
                || (kind == ScriptKind.CUSTOM ? digest == null : !digestNode.isNull())) {
            return null;
        }
        JsonNode stop = entry.path("stopReason");
        ScriptStopReason stopReason = stop.isString() ? STOP_REASONS.get(stop.asString()) : null;
        // A run that returned a candidate stopped for no reason; every other run names one.
        if ((status == ScriptRunStatus.OK) != stop.isNull() || (!stop.isNull() && stopReason == null)) {
            return null;
        }
        List<ScriptModelUse> models = modelUses(entry.path("models"));
        Map<String, Integer> calls = counts(entry.path("calls"), CALL_KINDS);
        Map<String, Integer> coverage = counts(entry.path("coverageReached"), COVERAGE_COUNTS);
        // Reports before the runner counted review sessions do not name them.
        Map<String, Integer> sessions =
                entry.has("reviewSessions") ? counts(entry.path("reviewSessions"), REVIEW_SESSION_COUNTS) : null;
        if (models == null || calls == null || coverage == null || (entry.has("reviewSessions") && sessions == null)) {
            return null;
        }
        // A program that did not start made no call and took no time.
        boolean started = status != ScriptRunStatus.NOT_REACHED;
        if (!started
                && (calls.values().stream().anyMatch(count -> count > 0)
                        || (sessions != null && sessions.values().stream().anyMatch(count -> count > 0)))) {
            return null;
        }
        String error = status == ScriptRunStatus.FAILED ? errorLine(entry.path("error")) : null;
        // A duration only explains the run, so a missing or malformed one leaves the run without it.
        Integer durationMs = started ? count(entry.path("durationMs")) : null;
        return new PracticeScriptRunReport(
                slug, status, kind, digest, stopReason, models, calls, coverage, sessions, error, durationMs);
    }

    private static @Nullable Map<String, Integer> counts(JsonNode node, List<String> names) {
        if (!node.isObject() || node.size() != names.size()) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : names) {
            Integer count = count(node.path(name));
            if (count == null) return null;
            counts.put(name, count);
        }
        return counts;
    }

    // Share error-line bounds with the runner; malformed optional errors must not invalidate the run's terminal write.
    private static @Nullable String errorLine(JsonNode error) {
        if (!error.isString()) return null;
        String text = BLANK_START.matcher(error.asString()).replaceFirst("");
        Matcher lineEnd = LINE_END.matcher(text);
        String first = lineEnd.find() ? text.substring(0, lineEnd.start()) : text;
        String cut = first.codePointCount(0, first.length()) <= PRACTICE_SCRIPT_ERROR_MAX_LENGTH
                ? first
                : first.substring(0, first.offsetByCodePoints(0, PRACTICE_SCRIPT_ERROR_MAX_LENGTH));
        String line = BLANK_END.matcher(CONTROL.matcher(cut).replaceAll(" ")).replaceFirst("");
        return line.isEmpty() || !StorableText.isStorable(line) ? null : line;
    }

    private static @Nullable List<ScriptModelUse> modelUses(JsonNode models) {
        if (!models.isArray()) return null;
        var uses = new ArrayList<ScriptModelUse>();
        var seen = new HashSet<ModelSlot>();
        for (JsonNode model : models) {
            ModelSlot slot = PURPOSES_BY_SLOT.get(model.path("slot").asString(""));
            Need need = PRACTICE_SCRIPT_NEEDS.get(model.path("need").asString(""));
            if (slot == null
                    || !seen.add(slot)
                    || need == null
                    || !model.path("bound").isBoolean()) return null;
            var failures = new ArrayList<ScriptModelUse.Failure>();
            JsonNode counts = model.path("failures");
            if (!counts.isObject()) return null;
            for (var entry : counts.properties()) {
                Integer amount = count(entry.getValue());
                if (amount == null || !MODEL_FAILURE_REASONS.contains(entry.getKey())) return null;
                if (amount > 0) failures.add(new ScriptModelUse.Failure(entry.getKey(), amount));
            }
            uses.add(new ScriptModelUse(slot, need, model.path("bound").asBoolean(), failures));
        }
        return List.copyOf(uses);
    }

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
