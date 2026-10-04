package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticeQuestion;
import de.tum.cit.aet.hephaestus.practices.PracticeRule;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ObservationAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates the answers a review submitted and derives each observation from them: the reviewer answers the
 * practice's questions; the practice revision's judgment, never the reviewer, decides the outcome and severity.
 */
public class ReviewResultParser {

    public static final int MAX_SUMMARY_LENGTH = 160;
    public static final int MAX_BECAUSE_LENGTH = 600;
    public static final int MAX_WOULD_SETTLE_IT_LENGTH = 600;
    private static final int MAX_EVIDENCE_BYTES = 64 * 1024;

    static final int MAX_MR_NOTE_LENGTH = 60_000;

    private static final Set<String> OBSERVATION_FIELDS = Set.of("practiceSlug", "summary", "answers");

    private static final Set<String> ANSWER_FIELDS =
            Set.of("question", "answer", "because", "citations", "search", "wouldSettleIt");

    private static final Set<String> SEARCH_FIELDS = Set.of("consulted", "lookedFor", "boundary");

    static final int MAX_DELIVERY_DIFF_NOTES = 30;

    private final JsonMapper objectMapper;

    public ReviewResultParser(JsonMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Validates the observations the runner submits for admission and derives each outcome.
     *
     * @param observations the submitted array
     * @param judgments    the judgment of every practice admitted to the job, by slug
     */
    public ParseResult parseObservations(@Nullable JsonNode observations, Map<String, PracticeJudgment> judgments) {
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
                valid.add(validateEntry(entry, judgments));
            } catch (EntryValidationException e) {
                discarded.add(new DiscardedEntry(i, String.valueOf(e.getMessage())));
            }
        }

        return new ParseResult(Collections.unmodifiableList(valid), Collections.unmodifiableList(discarded));
    }

    private ValidatedObservation validateEntry(JsonNode entry, Map<String, PracticeJudgment> judgments) {
        rejectUnknown(entry, OBSERVATION_FIELDS, "observation");
        String practiceSlug = textField(entry, "practiceSlug");
        if (practiceSlug.isBlank()) {
            throw new EntryValidationException("practiceSlug is blank");
        }
        practiceSlug = practiceSlug.toLowerCase(Locale.ROOT).replace('_', '-');
        PracticeJudgment judgment = judgments.get(practiceSlug);
        if (judgment == null) {
            // Not one bad entry but a run answering for work it was never given: nothing it recorded is trusted.
            throw new JobDeliveryException(
                    "Observation references a practice not admitted to the job: slug=" + practiceSlug);
        }

        String summary = textField(entry, "summary");
        if (summary.isBlank()) {
            throw new EntryValidationException("summary is blank");
        }
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            throw new EntryValidationException(
                    "summary is " + summary.length() + " characters, over the " + MAX_SUMMARY_LENGTH + " allowed");
        }

        Map<String, SubmittedAnswer> submitted = submittedAnswers(entry.get("answers"), judgment);
        Map<String, QuestionAnswer> values = new LinkedHashMap<>();
        submitted.forEach((key, answer) -> values.put(key, answer.answer()));
        PracticeJudgment.Derivation derived = judgment.derive(values);

        // Every quoted line is one citation of the observation, the deciding answers' first, each listed once.
        List<String> order = new ArrayList<>(derived.decisive());
        judgment.questions().stream()
                .map(PracticeQuestion::key)
                .filter(key -> !order.contains(key))
                .forEach(order::add);
        ArrayNode citations = objectMapper.createArrayNode();
        List<ObservationAnswer> answers = new ArrayList<>();
        Map<String, ObservationAnswer> byKey = new LinkedHashMap<>();
        for (String key : order) {
            SubmittedAnswer answer = submitted.get(key);
            if (answer == null) continue;
            List<Integer> indexes = new ArrayList<>();
            for (JsonNode citation : answer.citations()) {
                int index = indexOf(citations, citation);
                if (index < 0) {
                    citations.add(citation.deepCopy());
                    index = citations.size() - 1;
                }
                if (!indexes.contains(index)) indexes.add(index);
            }
            byKey.put(
                    key,
                    new ObservationAnswer(
                            key,
                            answer.answer(),
                            answer.because(),
                            derived.decisive().contains(key),
                            indexes,
                            answer.search(),
                            answer.wouldSettleIt()));
        }
        judgment.questions().stream()
                .map(question -> byKey.get(question.key()))
                .filter(Objects::nonNull)
                .forEach(answers::add);

        ObjectNode evidence = objectMapper.createObjectNode();
        evidence.set("citations", citations);
        List<ObservationAnswer> deciding = derived.decisive().stream()
                .map(key -> Objects.requireNonNull(byKey.get(key)))
                .toList();
        String titles = derived.decisive().stream()
                .map(key -> Objects.requireNonNull(judgment.question(key)).title())
                .collect(Collectors.joining("; "));
        PracticeRule rule = derived.rule();
        String headline = rule != null
                ? rule.reason()
                : Objects.requireNonNullElse(
                        PracticeJudgment.unsettledHeadline(derived.outcome()),
                        "The evidence leaves open: " + titles + ".");
        switch (derived.outcome()) {
            case NOT_APPLICABLE -> {
                ObjectNode inapplicability = evidence.putObject("inapplicability");
                ArrayNode consulted = inapplicability.putArray("consulted");
                consultedSources(deciding, citations).forEach(consulted::add);
                inapplicability.put("subject", titles);
                inapplicability.put("ruledOutBy", headline);
            }
            case UNDETERMINED -> {
                ObjectNode undecidability = evidence.putObject("undecidability");
                undecidability.put(
                        "openQuestion", rule != null ? rule.reason() : "The evidence leaves open: " + titles);
                String settle = deciding.stream()
                        .map(ObservationAnswer::wouldSettleIt)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("; "));
                if (!settle.isBlank()) undecidability.put("wouldSettleIt", settle);
            }
            case MET, NOT_MET -> {
                List<ObservationAnswer.Search> searches = deciding.stream()
                        .map(ObservationAnswer::search)
                        .filter(Objects::nonNull)
                        .toList();
                if (!searches.isEmpty()) {
                    ObjectNode search = evidence.putObject("search");
                    ArrayNode consulted = search.putArray("consulted");
                    searches.stream()
                            .flatMap(found -> found.consulted().stream())
                            .distinct()
                            .forEach(consulted::add);
                    search.put(
                            "lookedFor",
                            searches.stream()
                                    .map(ObservationAnswer.Search::lookedFor)
                                    .distinct()
                                    .collect(Collectors.joining("; ")));
                    search.put(
                            "boundary",
                            searches.stream()
                                    .map(ObservationAnswer.Search::boundary)
                                    .distinct()
                                    .collect(Collectors.joining("; ")));
                }
            }
        }
        try {
            if (objectMapper.writeValueAsBytes(evidence).length + objectMapper.writeValueAsBytes(answers).length
                    > MAX_EVIDENCE_BYTES) {
                throw new EntryValidationException("evidence exceeds " + MAX_EVIDENCE_BYTES + " bytes");
            }
        } catch (JacksonException e) {
            throw new EntryValidationException("invalid evidence JSON", e);
        }

        String rationale = Stream.concat(Stream.of(headline), deciding.stream().map(ObservationAnswer::because))
                .collect(Collectors.joining(" "));
        return new ValidatedObservation(
                practiceSlug,
                summary,
                derived.outcome(),
                derived.severity(),
                evidence,
                rationale,
                null,
                PracticeDeliveryBehavior.DEFAULT,
                List.copyOf(answers),
                rule == null ? null : rule.id());
    }

    private record SubmittedAnswer(
            QuestionAnswer answer,
            String because,
            List<JsonNode> citations,
            ObservationAnswer.@Nullable Search search,
            @Nullable String wouldSettleIt) {}

    private Map<String, SubmittedAnswer> submittedAnswers(@Nullable JsonNode node, PracticeJudgment judgment) {
        if (node == null || !node.isArray()) {
            throw new EntryValidationException("missing or non-array field: answers");
        }
        Map<String, SubmittedAnswer> answers = new LinkedHashMap<>();
        for (JsonNode answer : node) {
            if (!answer.isObject()) {
                throw new EntryValidationException("every answer must be an object");
            }
            rejectUnknown(answer, ANSWER_FIELDS, "answer");
            String key = textField(answer, "question");
            if (judgment.question(key) == null) {
                throw new EntryValidationException("unknown question: " + key);
            }
            QuestionAnswer value = parseEnum(answer, "answer", QuestionAnswer.class);
            String because = textField(answer, "because").strip();
            if (because.isBlank() || because.length() > MAX_BECAUSE_LENGTH) {
                throw new EntryValidationException(
                        "answer " + key + ": because must be 1–" + MAX_BECAUSE_LENGTH + " characters");
            }
            JsonNode citations = answer.get("citations");
            if (citations == null || !citations.isArray() || citations.isEmpty()) {
                throw new EntryValidationException("answer " + key + ": at least one citation is required");
            }
            List<JsonNode> cited = new ArrayList<>();
            for (JsonNode citation : citations) {
                if (!citation.isObject()) {
                    throw new EntryValidationException("answer " + key + ": every citation must be an object");
                }
                cited.add(citation);
            }
            JsonNode wouldSettle = answer.get("wouldSettleIt");
            String wouldSettleIt = wouldSettle == null || wouldSettle.isNull()
                    ? null
                    : wouldSettle.asString("").strip();
            if (value == QuestionAnswer.UNDETERMINED
                    && (wouldSettleIt == null
                            || wouldSettleIt.isBlank()
                            || wouldSettleIt.length() > MAX_WOULD_SETTLE_IT_LENGTH)) {
                throw new EntryValidationException("answer " + key
                        + ": an UNDETERMINED answer must name, in wouldSettleIt, the evidence that would decide it");
            }
            if (value != QuestionAnswer.UNDETERMINED && wouldSettleIt != null) {
                throw new EntryValidationException("answer " + key + ": wouldSettleIt is only for UNDETERMINED");
            }
            if (answers.put(
                            key,
                            new SubmittedAnswer(
                                    value, because, cited, search(answer.get("search"), key), wouldSettleIt))
                    != null) {
                throw new EntryValidationException("question " + key + " is answered twice");
            }
        }
        // A question another answer makes moot need not be answered, nor one that only grades severity, which is read
        // as open; every other one must be.
        Map<String, List<PracticeJudgment.SkipCondition>> skips = judgment.skipConditions();
        Set<String> severityOnly = judgment.gradesSeverityOnly();
        List<String> missing = judgment.questions().stream()
                .map(PracticeQuestion::key)
                .filter(key -> !answers.containsKey(key) && !severityOnly.contains(key))
                .filter(key -> Objects.requireNonNull(skips.get(key)).stream().noneMatch(skip -> {
                    SubmittedAnswer gate = answers.get(skip.question());
                    return gate != null && gate.answer() == skip.answer();
                }))
                .toList();
        if (!missing.isEmpty()) {
            throw new EntryValidationException("unanswered questions: " + missing);
        }
        return answers;
    }

    private static ObservationAnswer.@Nullable Search search(@Nullable JsonNode node, String key) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new EntryValidationException("answer " + key + ": search must be an object");
        }
        rejectUnknown(node, SEARCH_FIELDS, "search");
        JsonNode consulted = node.get("consulted");
        if (consulted == null || !consulted.isArray() || consulted.isEmpty()) {
            throw new EntryValidationException("answer " + key + ": search.consulted must name the sources searched");
        }
        List<String> sources = new ArrayList<>();
        for (JsonNode source : consulted) {
            if (!source.isString() || source.asString().isBlank()) {
                throw new EntryValidationException("answer " + key + ": search.consulted holds a non-text source");
            }
            sources.add(source.asString());
        }
        String lookedFor = textField(node, "lookedFor");
        String boundary = textField(node, "boundary");
        if (lookedFor.isBlank() || boundary.isBlank()) {
            throw new EntryValidationException("answer " + key + ": search needs lookedFor and boundary");
        }
        return new ObservationAnswer.Search(sources, lookedFor, boundary);
    }

    /** The sources the deciding answers read: what they cite and where they searched, in order. */
    private static List<String> consultedSources(List<ObservationAnswer> deciding, ArrayNode citations) {
        Set<String> sources = new LinkedHashSet<>();
        for (ObservationAnswer answer : deciding) {
            answer.citations()
                    .forEach(index ->
                            sources.add(citations.get(index).path("sourceKind").asString()));
            if (answer.search() != null) sources.addAll(answer.search().consulted());
        }
        sources.remove("");
        return List.copyOf(sources);
    }

    private static int indexOf(ArrayNode citations, JsonNode citation) {
        for (int i = 0; i < citations.size(); i++) {
            if (citations.get(i).equals(citation)) return i;
        }
        return -1;
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String what) {
        List<String> unknown = node.properties().stream()
                .map(Map.Entry::getKey)
                .filter(field -> !allowed.contains(field))
                .toList();
        if (!unknown.isEmpty()) {
            throw new EntryValidationException("unknown " + what + " fields: " + unknown);
        }
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
            PracticeDeliveryBehavior deliveryBehavior,
            @Nullable List<ObservationAnswer> answers,
            @Nullable String ruleId) {
        public ValidatedObservation(
                String practiceSlug,
                String summary,
                Outcome outcome,
                @Nullable Severity severity,
                @Nullable JsonNode evidence,
                @Nullable String evidenceRationale,
                @Nullable ObservationKeys keys,
                PracticeDeliveryBehavior deliveryBehavior) {
            this(
                    practiceSlug,
                    summary,
                    outcome,
                    severity,
                    evidence,
                    evidenceRationale,
                    keys,
                    deliveryBehavior,
                    null,
                    null);
        }

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

        /** An observation not yet stamped with its persisted identities. */
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
                    practiceSlug,
                    summary,
                    outcome,
                    severity,
                    evidence,
                    evidenceRationale,
                    keys,
                    deliveryBehavior,
                    answers,
                    ruleId);
        }

        /** The same answers and derivation with the evidence verification recorded on its citations. */
        public ValidatedObservation withEvidence(@Nullable JsonNode verified) {
            return new ValidatedObservation(
                    practiceSlug,
                    summary,
                    outcome,
                    severity,
                    verified,
                    evidenceRationale,
                    keys,
                    deliveryBehavior,
                    answers,
                    ruleId);
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
     */
    public record DeliveryContent(
            @Nullable String mrNote,
            List<DiffNote> diffNotes,
            List<WithheldObservation> withheld,
            @Nullable List<String> summaryContributors) {
        public DeliveryContent withDiffNotes(List<DiffNote> notes) {
            return new DeliveryContent(mrNote, notes, withheld, summaryContributors);
        }

        /** The same decisions with nothing to place on the work. */
        public DeliveryContent withoutNote() {
            return new DeliveryContent(null, List.of(), withheld, List.of());
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
     * @param deliveryKey opaque receipt-correlation key for this exact observation, carried from its
     *     occurrence identity by {@link DeliveryComposer}; null before server-side correlation.
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
