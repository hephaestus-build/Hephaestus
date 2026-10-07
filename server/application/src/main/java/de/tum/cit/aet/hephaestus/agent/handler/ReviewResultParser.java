package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validates submitted observations into typed ones without throwing on malformed entries. */
public class ReviewResultParser {

    public static final int MAX_SUMMARY_LENGTH = 160;
    private static final int MAX_EVIDENCE_RATIONALE_LENGTH = 10_000;
    private static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    static final int MAX_MR_NOTE_LENGTH = 60_000;

    private static final Set<String> OBSERVATION_FIELDS =
            Set.of("practiceSlug", "summary", "outcome", "severity", "evidence", "evidenceRationale");

    private static final Set<String> EVIDENCE_FIELDS =
            Set.of("citations", "search", "inapplicability", "undecidability");

    /** The line notes one review may carry; the composition contract owns the number. */
    static final int MAX_DELIVERY_DIFF_NOTES = ComposedReview.MAX_INLINE_NOTES;

    private final JsonMapper objectMapper;

    public ReviewResultParser(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Validates an already-structured observations array, as the runner submits it for admission. */
    public ParseResult parseObservations(@Nullable JsonNode observations) {
        if (observations == null || !observations.isArray()) {
            return ParseResult.empty("missing or non-array 'observations' field");
        }
        if (observations.isEmpty()) {
            return ParseResult.empty("observations array is empty");
        }

        List<ValidatedObservation> valid = new ArrayList<>();
        List<DiscardedEntry> discarded = new ArrayList<>();
        for (int i = 0; i < observations.size(); i++) {
            JsonNode entry = observations.get(i);
            if (!entry.isObject()) {
                discarded.add(new DiscardedEntry(i, "entry is not a JSON object"));
                continue;
            }
            try {
                valid.add(validateEntry(entry));
            } catch (EntryValidationException e) {
                discarded.add(new DiscardedEntry(i, String.valueOf(e.getMessage())));
            }
        }

        return new ParseResult(Collections.unmodifiableList(valid), Collections.unmodifiableList(discarded));
    }

    private ValidatedObservation validateEntry(JsonNode entry) {
        List<String> unknownFields = entry.properties().stream()
                .map(Entry::getKey)
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
            throw new EntryValidationException(
                    "summary is " + summary.length() + " characters, over the " + MAX_SUMMARY_LENGTH + " allowed");
        }

        Outcome outcome = parseEnum(entry, "outcome", Outcome.class);
        Severity severity = parseNullableEnum(entry, "severity", Severity.class);
        try {
            outcome.validate(severity);
        } catch (IllegalArgumentException e) {
            throw new EntryValidationException("incoherent outcome: " + e.getMessage(), e);
        }

        JsonNode evidence = entry.get("evidence");
        if (evidence == null || !evidence.isObject()) {
            throw new EntryValidationException("missing or non-object field: evidence");
        }
        List<String> unknownEvidenceFields = evidence.properties().stream()
                .map(Entry::getKey)
                .filter(field -> !EVIDENCE_FIELDS.contains(field))
                .toList();
        if (!unknownEvidenceFields.isEmpty()) {
            throw new EntryValidationException("unknown evidence fields: " + unknownEvidenceFields);
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

        return new ValidatedObservation(practiceSlug, summary, outcome, severity, evidence, evidenceRationale);
    }

    private static <E extends Enum<E>> @Nullable E parseNullableEnum(JsonNode entry, String field, Class<E> enumType) {
        JsonNode node = entry.get(field);
        if (node == null) throw new EntryValidationException("missing field: " + field);
        return node.isNull() ? null : parseEnum(entry, field, enumType);
    }

    private static String textField(JsonNode entry, String field) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new EntryValidationException("missing or non-text field: " + field);
        }
        return node.asString();
    }

    private static <E extends Enum<E>> E parseEnum(JsonNode entry, String field, Class<E> enumType) {
        JsonNode node = entry.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new EntryValidationException("missing or non-text field: " + field);
        }
        try {
            return Enum.valueOf(enumType, node.asString());
        } catch (IllegalArgumentException e) {
            throw new EntryValidationException("invalid " + field + " value: '" + node.asString() + "'", e);
        }
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
     * @param keys the identities {@code ReviewOutputService.deliver} persisted for this observation,
     *     stamped by the handler rather than recomputed downstream so they cannot drift from the stored
     *     observation. {@code null} until stamped — the parser leaves it unset.
     */
    public record ValidatedObservation(
            String practiceSlug,
            String summary,
            Outcome outcome,
            @Nullable Severity severity,
            @Nullable JsonNode evidence,
            @Nullable String evidenceRationale,
            @Nullable ObservationKeys keys,
            PracticeDeliveryBehavior deliveryBehavior) {
        public ValidatedObservation(
                String practiceSlug,
                String summary,
                Outcome outcome,
                @Nullable Severity severity,
                @Nullable JsonNode evidence,
                @Nullable String evidenceRationale,
                @Nullable ObservationKeys keys) {
            this(
                    practiceSlug,
                    summary,
                    outcome,
                    severity,
                    evidence,
                    evidenceRationale,
                    keys,
                    PracticeDeliveryBehavior.DEFAULT);
        }
        /** The parser's output shape: an observation not yet stamped with its persisted identities. */
        public ValidatedObservation(
                String practiceSlug,
                String summary,
                Outcome outcome,
                @Nullable Severity severity,
                @Nullable JsonNode evidence,
                @Nullable String evidenceRationale) {
            this(
                    practiceSlug,
                    summary,
                    outcome,
                    severity,
                    evidence,
                    evidenceRationale,
                    null,
                    PracticeDeliveryBehavior.DEFAULT);
        }

        public ValidatedObservation withKeys(@Nullable ObservationKeys keys) {
            return new ValidatedObservation(
                    practiceSlug, summary, outcome, severity, evidence, evidenceRationale, keys, deliveryBehavior);
        }

        public @Nullable String recurrenceKey() {
            return keys == null ? null : keys.recurrenceKey();
        }

        public @Nullable String occurrenceKey() {
            return keys == null ? null : keys.occurrenceKey();
        }

        public @Nullable UUID observationId() {
            return keys == null ? null : keys.id();
        }
    }

    /** Validates outcome and severity without changing either. */
    public static List<ValidatedObservation> validateCoherence(List<ValidatedObservation> observations) {
        for (ValidatedObservation observation : observations) {
            observation.outcome().validate(observation.severity());
        }
        return new ArrayList<>(observations);
    }

    public record DiscardedEntry(int index, String reason) {}

    /**
     * Pre-rendered delivery content from the agent, alongside the structured observations — the server sanitizes
     * and posts it without further rendering.
     *
     * @param withheld the observations the composer chose not to render, for the ledger to record as SUPPRESSED
     * @param summaryContributors occurrence keys of the observations the summary note was written from; each line
     *     note carries its own. Null only on a dispatch package persisted before they were recorded
     * @param inlineMarker the marker every inline copy of this package carries, sealed with it; null on a package
     *     persisted before it was recorded, whose copies keep the historical marker and readback
     */
    public record DeliveryContent(
            @Nullable String mrNote,
            List<DiffNote> diffNotes,
            List<WithheldObservation> withheld,
            @Nullable List<String> summaryContributors,
            @Nullable String inlineMarker) {
        public DeliveryContent(
                @Nullable String mrNote,
                List<DiffNote> diffNotes,
                List<WithheldObservation> withheld,
                @Nullable List<String> summaryContributors) {
            this(mrNote, diffNotes, withheld, summaryContributors, null);
        }

        public DeliveryContent withDiffNotes(List<DiffNote> notes) {
            return new DeliveryContent(mrNote, notes, withheld, summaryContributors, inlineMarker);
        }

        /** The same decisions with nothing to place on the work. */
        public DeliveryContent withoutNote() {
            return new DeliveryContent(null, List.of(), withheld, List.of(), inlineMarker);
        }

        /** Every observation some part of the content was written from; null on a pre-upgrade package. */
        public @Nullable List<String> contributors() {
            if (summaryContributors == null) return null;
            List<String> keys = new ArrayList<>(summaryContributors);
            for (DiffNote note : diffNotes) {
                List<String> noteKeys = note.contributors();
                if (noteKeys != null) keys.addAll(noteKeys);
            }
            return keys.stream().distinct().toList();
        }

        /** Whether the content was written from any of these observations. */
        public boolean writtenFromAny(List<ValidatedObservation> observations) {
            List<String> keys = contributors();
            return keys != null
                    && observations.stream()
                            .map(ValidatedObservation::occurrenceKey)
                            .anyMatch(keys::contains);
        }

        /** The practices of the observations the content was written from, which delivery policy checks. */
        public Set<String> contributingPracticeSlugs(List<ValidatedObservation> observations) {
            List<String> contributors = contributors();
            List<String> keys = contributors == null ? List.of() : contributors;
            return observations.stream()
                    .filter(observation -> keys.contains(observation.occurrenceKey()))
                    .map(ValidatedObservation::practiceSlug)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * An observation the review withheld from the rendered delivery, identified by the
     * {@code occurrenceKey} of the observation it was persisted as — a per-observation identity, so a
     * withheld observation is never confused with another at the same locus.
     */
    public record WithheldObservation(String occurrenceKey, FeedbackSuppressionReason reason) {}

    /**
     * An inline diff note targeting a specific file and line range.
     *
     * @param filePath path relative to repo root (new path, not old)
     * @param endLine  optional last line number for multi-line (GitHub only; GitLab ignores)
     * @param deliveryKey opaque receipt-correlation key for this exact observation, carried from its
     *     occurrence identity by {@link ComposedReviewAdmission}; null before server-side correlation.
     * @param contributors occurrence keys of every observation this note's text was written from: its own, and
     *     any other the composed unit cites. Null before server-side correlation and on a pre-upgrade package
     */
    public record DiffNote(
            String filePath,
            int startLine,
            @Nullable Integer endLine,
            String body,
            @Nullable String deliveryKey,
            @Nullable List<String> contributors) {
        /** The parser's pre-correlation output shape: a note with no correlation key yet. */
        public DiffNote(String filePath, int startLine, @Nullable Integer endLine, String body) {
            this(filePath, startLine, endLine, body, null, null);
        }
    }
}
