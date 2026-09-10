package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parses normalized agent output into validated observations without throwing on malformed entries. */
public class PracticeDetectionResultParser {

    private static final Logger log = LoggerFactory.getLogger(PracticeDetectionResultParser.class);

    private static final int MAX_SUMMARY_LENGTH = 255;
    private static final int MAX_EVIDENCE_RATIONALE_LENGTH = 10_000;
    private static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    private static final int MAX_RAW_OUTPUT_LENGTH = 1_000_000;

    static final int MAX_MR_NOTE_LENGTH = 60_000;

    /** Only practices with correctness, security, or integrity consequences may block a merge. */
    static final Set<String> BLOCKING_ELIGIBLE_PRACTICES = Set.of(
            "handles-errors-instead-of-swallowing-them",
            "validates-inputs-and-edge-cases-at-the-boundary",
            "avoids-unsafe-panics-and-chosen-crashes",
            "validates-and-escapes-untrusted-input",
            "avoids-insecure-defaults-and-over-broad-permissions",
            "keeps-the-test-suite-honest");

    private static final Set<String> OBSERVATION_FIELDS =
            Set.of("practiceSlug", "summary", "presence", "assessment", "severity", "evidence", "evidenceRationale");

    static final int MAX_DELIVERY_DIFF_NOTES = 30;

    private final JsonMapper objectMapper;
    private final JsonMapper lenientMapper;

    public PracticeDetectionResultParser(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
        // LLMs produce JSON with literal newlines/tabs/control chars inside string values that strict JSON rejects.
        this.lenientMapper = objectMapper
                .rebuild()
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
    }

    public ParseResult parse(@Nullable JsonNode jobOutput) {
        if (jobOutput == null || jobOutput.isNull() || jobOutput.isMissingNode()) {
            return ParseResult.empty("jobOutput is null or missing");
        }
        JsonNode rawOutputNode = jobOutput.get("rawOutput");
        if (rawOutputNode == null || rawOutputNode.isNull() || rawOutputNode.isMissingNode()) {
            return ParseResult.empty("missing rawOutput field in job output");
        }
        String rawOutputText = rawOutputNode.asString();
        if (rawOutputText.isBlank()) {
            return ParseResult.empty("rawOutput is blank");
        }
        // Reject before parsing to bound memory use on untrusted model output.
        if (rawOutputText.length() > MAX_RAW_OUTPUT_LENGTH) {
            log.warn("parse: rawOutput too large ({} chars), skipping", rawOutputText.length());
            return ParseResult.empty("rawOutput too large");
        }

        String sanitizedText = sanitizeJsonEscapes(rawOutputText);
        JsonNode root;
        try {
            root = lenientMapper.readTree(sanitizedText);
        } catch (JacksonException e) {
            root = extractJsonFromText(sanitizedText);
            if (root == null) {
                return ParseResult.empty("invalid JSON in rawOutput: " + e.getMessage());
            }
        }
        if (root == null || root.isNull()) {
            return ParseResult.empty("rawOutput parsed to null");
        }
        JsonNode observationsNode = extractObservationsNode(root);
        if (observationsNode == null || !observationsNode.isArray()) {
            return ParseResult.empty("missing or non-array 'observations' field");
        }
        if (observationsNode.isEmpty()) {
            return ParseResult.empty("observations array is empty");
        }

        List<ValidatedObservation> valid = new ArrayList<>();
        List<DiscardedEntry> discarded = new ArrayList<>();
        for (int i = 0; i < observationsNode.size(); i++) {
            JsonNode entry = observationsNode.get(i);
            if (!entry.isObject()) {
                discarded.add(new DiscardedEntry(i, "entry is not a JSON object"));
                continue;
            }
            try {
                valid.add(validateEntry(entry, i));
            } catch (EntryValidationException e) {
                discarded.add(new DiscardedEntry(i, String.valueOf(e.getMessage())));
            }
        }

        return new ParseResult(Collections.unmodifiableList(valid), Collections.unmodifiableList(discarded));
    }

    private JsonNode extractObservationsNode(JsonNode root) {
        return root.get("observations");
    }

    private ValidatedObservation validateEntry(JsonNode entry, int index) {
        List<String> unknownFields = entry.properties().stream()
                .map(java.util.Map.Entry::getKey)
                .filter(field -> !OBSERVATION_FIELDS.contains(field))
                .toList();
        if (!unknownFields.isEmpty()) {
            throw new EntryValidationException("unknown observation fields: " + unknownFields);
        }
        String practiceSlug = textField(entry, "practiceSlug");
        if (practiceSlug.isBlank()) {
            throw new EntryValidationException("practiceSlug is blank");
        }
        practiceSlug = practiceSlug.toLowerCase(Locale.ROOT).replace('_', '-');

        String summary = textField(entry, "summary");
        if (summary.isBlank()) {
            throw new EntryValidationException("summary is blank");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new EntryValidationException("summary exceeds " + MAX_SUMMARY_LENGTH + " characters");
        }

        Presence presence = parseEnum(entry, "presence", Presence.class);

        Assessment assessment = parseAssessment(entry, presence);
        Severity severity = parseSeverityOrDefault(entry);

        JsonNode evidence = entry.get("evidence");
        if (evidence == null || !evidence.isObject()) {
            throw new EntryValidationException("missing or non-object field: evidence");
        }
        try {
            if (objectMapper.writeValueAsBytes(evidence).length > MAX_EVIDENCE_BYTES) {
                throw new EntryValidationException("evidence exceeds " + MAX_EVIDENCE_BYTES + " bytes");
            }
        } catch (JacksonException e) {
            throw new EntryValidationException("invalid evidence JSON", e);
        }

        String evidenceRationale = textField(entry, "evidenceRationale");
        if (evidenceRationale.isBlank()) {
            throw new EntryValidationException("evidenceRationale is blank");
        }
        if (evidenceRationale.length() > MAX_EVIDENCE_RATIONALE_LENGTH) {
            throw new EntryValidationException(
                    "evidenceRationale exceeds " + MAX_EVIDENCE_RATIONALE_LENGTH + " characters");
        }

        return new ValidatedObservation(
                practiceSlug, summary, presence, assessment, severity, evidence, evidenceRationale);
    }

    /** Assessment exists only for outcomes that carry valence. */
    private static @Nullable Assessment parseAssessment(JsonNode entry, Presence presence) {
        if (!presence.carriesValence()) {
            return null;
        }
        return parseEnum(entry, "assessment", Assessment.class);
    }

    private static String textField(JsonNode entry, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new EntryValidationException("missing or non-text field: " + field);
        }
        return node.asString();
    }

    /**
     * A missing, null, or non-text value defaults to {@link Severity#INFO} rather than discarding the
     * observation: {@link ValidatedObservation#coerceCoherence(boolean, boolean)} re-derives the real band anyway. A
     * present but unrecognised value still fails the entry.
     */
    private static Severity parseSeverityOrDefault(JsonNode entry) {
        JsonNode node = entry.get("severity");
        if (node == null || node.isNull() || !node.isString()) {
            return Severity.INFO;
        }
        return parseEnum(entry, "severity", Severity.class);
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode entry, String field, Class<E> enumType) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new EntryValidationException("missing or non-text field: " + field);
        }
        try {
            return Enum.valueOf(enumType, node.asString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new EntryValidationException("invalid " + field + " value: '" + node.asString() + "'", e);
        }
    }

    /**
     * Doubles any backslash that precedes a character invalid after {@code \} in JSON (only
     * {@code " \ / b f n r t u} are valid), turning e.g. Swift's {@code \(var)} interpolation into a literal
     * backslash Jackson can read rather than a malformed escape.
     */
    static String sanitizeJsonEscapes(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (isValidJsonEscapeChar(next)) {
                    sb.append(c);
                    sb.append(next);
                    i++;
                } else {
                    sb.append('\\');
                    // Don't skip `next`: it is not itself a backslash, so it still needs its own pass.
                    sb.append('\\');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isValidJsonEscapeChar(char c) {
        return (c == '"' || c == '\\' || c == '/' || c == 'b' || c == 'f' || c == 'n' || c == 'r' || c == 't'
                || c == 'u');
    }

    /**
     * The orchestrator protocol emits phase markers (e.g. {@code [PHASE0]...}) before its JSON object; this
     * finds the first {@code '{'} that starts a valid object containing an "observations" array.
     */
    @Nullable
    private JsonNode extractJsonFromText(String text) {
        if (text.length() > MAX_RAW_OUTPUT_LENGTH) {
            log.warn("extractJsonFromText: input too large ({} chars), skipping", text.length());
            return null;
        }
        int startIdx = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            int braceIdx = text.indexOf('{', startIdx);
            if (braceIdx < 0) break;
            try {
                JsonNode node = lenientMapper.readTree(text.substring(braceIdx));
                if (node != null && node.isObject() && node.has("observations")) {
                    return node;
                }
            } catch (JacksonException ignored) {
                // try the next '{'
            }
            startIdx = braceIdx + 1;
        }
        return null;
    }

    private static class EntryValidationException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        EntryValidationException(String message) {
            super(message);
        }

        EntryValidationException(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this; // Flow-control exception — skip expensive stack trace capture
        }
    }

    public record ParseResult(List<ValidatedObservation> validObservations, List<DiscardedEntry> discarded) {
        static ParseResult empty(String reason) {
            return new ParseResult(List.of(), List.of(new DiscardedEntry(-1, reason)));
        }
    }

    /**
     * @param keys the identities {@code PracticeDetectionDeliveryService.deliver} persisted for this observation,
     *     stamped by the handler rather than recomputed downstream so they cannot drift from the stored
     *     observation. {@code null} until stamped — the parser leaves it unset.
     */
    public record ValidatedObservation(
            String practiceSlug,
            String summary,
            Presence presence,
            @Nullable Assessment assessment,
            @Nullable Severity severity,
            @Nullable JsonNode evidence,
            @Nullable String evidenceRationale,
            @Nullable ObservationKeys keys) {
        /** The parser's output shape: an observation not yet stamped with its persisted identities. */
        public ValidatedObservation(
                String practiceSlug,
                String summary,
                Presence presence,
                @Nullable Assessment assessment,
                @Nullable Severity severity,
                @Nullable JsonNode evidence,
                @Nullable String evidenceRationale) {
            this(practiceSlug, summary, presence, assessment, severity, evidence, evidenceRationale, null);
        }

        public ValidatedObservation withKeys(@Nullable ObservationKeys keys) {
            return new ValidatedObservation(
                    practiceSlug, summary, presence, assessment, severity, evidence, evidenceRationale, keys);
        }

        public @Nullable String recurrenceKey() {
            return keys == null ? null : keys.recurrenceKey();
        }

        public @Nullable String occurrenceKey() {
            return keys == null ? null : keys.occurrenceKey();
        }

        /** Enforces valence and severity invariants independently of model output. */
        public ValidatedObservation coerceCoherence(boolean isDefectDetector, boolean advisoryOnly) {
            Presence p = presence;
            Assessment a = assessment;
            if (isDefectDetector && a == Assessment.GOOD && p == Presence.PRESENT) {
                throw new ObservationsRefusedException(
                        "incoherent_assessment",
                        "Practice " + practiceSlug
                                + " targets harmful behaviour: PRESENT/GOOD is inconsistent. Reassess the original"
                                + " evidence; inconsistency does not establish that the practice is inapplicable.");
            }
            if (!p.carriesValence()) {
                a = null;
            }
            Severity s = a == Assessment.BAD
                    ? (severity == null || severity == Severity.INFO ? Severity.MINOR : severity)
                    : null;
            if (advisoryOnly && a == Assessment.BAD && (s == Severity.CRITICAL || s == Severity.MAJOR)) {
                s = Severity.MINOR;
            }
            if ("avoids-insecure-defaults-and-over-broad-permissions".equals(practiceSlug) && s == Severity.CRITICAL) {
                s = Severity.MAJOR;
            }
            if (p == presence && a == assessment && s == severity) {
                return this;
            }
            return new ValidatedObservation(practiceSlug, summary, p, a, s, evidence, evidenceRationale);
        }
    }

    /** Applies coherence rules to all observations and returns a mutable result. */
    public static List<ValidatedObservation> coerceCoherence(
            List<ValidatedObservation> observations, Set<String> defectDetectorSlugs) {
        List<ValidatedObservation> out = new ArrayList<>(observations.size());
        for (ValidatedObservation f : observations) {
            boolean advisoryOnly = !BLOCKING_ELIGIBLE_PRACTICES.contains(f.practiceSlug());
            out.add(f.coerceCoherence(defectDetectorSlugs.contains(f.practiceSlug()), advisoryOnly));
        }
        return out;
    }

    public record DiscardedEntry(int index, String reason) {}

    /**
     * Pre-rendered delivery content from the agent, alongside the structured observations — the server sanitizes
     * and posts it without further rendering.
     *
     * @param withheld the observations the composer chose not to render, for the ledger to record as SUPPRESSED
     */
    public record DeliveryContent(
            @Nullable String mrNote, List<DiffNote> diffNotes, List<WithheldObservation> withheld) {
        public DeliveryContent withDiffNotes(List<DiffNote> notes) {
            return new DeliveryContent(mrNote, notes, withheld);
        }
    }

    /**
     * An observation the {@link DeliveryComposer} withheld from the rendered delivery, identified by the
     * {@code occurrenceKey} of the observation it was persisted as — a per-observation identity, so a
     * withheld observation is never confused with another at the same locus.
     */
    public record WithheldObservation(String occurrenceKey, FeedbackSuppressionReason reason) {}

    /**
     * An inline diff note targeting a specific file and line range.
     *
     * @param filePath path relative to repo root (new path, not old)
     * @param endLine  optional last line number for multi-line (GitHub only; GitLab ignores)
     * @param recurrenceKey the stable cross-run identity inherited from the observation this note belongs to, so a
     *     posted placement can be matched back across re-runs; {@code null} until {@link DeliveryComposer}
     *     carries it over from the stamped observation.
     */
    public record DiffNote(
            String filePath,
            int startLine,
            @Nullable Integer endLine,
            String body,
            @Nullable String recurrenceKey) {
        /** The parser's pre-correlation output shape: a note with no correlation key yet. */
        public DiffNote(String filePath, int startLine, @Nullable Integer endLine, String body) {
            this(filePath, startLine, endLine, body, null);
        }
    }
}
