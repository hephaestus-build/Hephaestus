package de.tum.cit.aet.hephaestus.agent.handler.composition;

import de.tum.cit.aet.hephaestus.practices.feedback.DeveloperTextSanitizer;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class FeedbackCompositionResultParser {

    private static final Logger log = LoggerFactory.getLogger(FeedbackCompositionResultParser.class);

    public static final String OUTPUT_KEY = "feedback";

    /**
     * A bound on how much composed prose one cycle can offer, before each lane's per-recipient cap
     * applies. Not a policy — a guard against a runaway turn filling the ledger.
     */
    static final int MAX_UNITS = 30;

    private static final int MAX_PRACTICE_SLUG_LENGTH = 128;

    private static final int MAX_OBSERVATION_ID_LENGTH = 64;

    public static final String INCOMPLETE_COMPOSITION_MESSAGE = "Some feedback could not be composed.";

    public enum CompositionStatus {
        LEGACY_UNKNOWN,
        NO_RECORDED_FAILURE,
        INCOMPLETE,
        INVALID;

        public @Nullable String message() {
            return switch (this) {
                case INCOMPLETE -> INCOMPLETE_COMPOSITION_MESSAGE;
                case INVALID -> "Feedback composition status could not be read.";
                case LEGACY_UNKNOWN, NO_RECORDED_FAILURE -> null;
            };
        }

        public boolean failed() {
            return this == INCOMPLETE || this == INVALID;
        }
    }

    /** Missing legacy metadata is unknown; malformed present metadata cannot certify quiet output. */
    public static CompositionStatus compositionStatus(@Nullable JsonNode jobOutput) {
        JsonNode payload = payloadOf(jobOutput);
        JsonNode failures = payload == null ? null : payload.get("compositionFailures");
        if (failures == null) return CompositionStatus.LEGACY_UNKNOWN;
        if (!failures.isArray() || failures.size() > 2) return CompositionStatus.INVALID;
        Set<String> phases = new HashSet<>();
        Set<String> reasons =
                Set.of("MODEL_ERROR", "RUNTIME_ERROR", "BUDGET", "STALL", "SAFETY", "LOOP", "NO_DECISION");
        for (JsonNode failure : failures) {
            JsonNode phase = failure.get("phase");
            JsonNode reason = failure.get("reason");
            if (!failure.isObject()
                    || !onlyFields(failure, "phase", "reason")
                    || phase == null
                    || !phase.isString()
                    || reason == null
                    || !reason.isString()
                    || !Set.of("PUBLIC_REVIEW", "PRIVATE_FEEDBACK").contains(phase.asString())
                    || !phases.add(phase.asString())
                    || !reasons.contains(reason.asString())) {
                return CompositionStatus.INVALID;
            }
        }
        return failures.isEmpty() ? CompositionStatus.NO_RECORDED_FAILURE : CompositionStatus.INCOMPLETE;
    }

    /** The private-lane units of a composition: the developer's practice pages and the mentor conversation. */
    public List<ComposedFeedbackUnit> parse(@Nullable JsonNode jobOutput) {
        JsonNode payload = payloadOf(jobOutput);
        if (payload == null) {
            return List.of();
        }
        JsonNode units = payload.get("units");
        if (units == null || !units.isArray()) {
            return List.of();
        }
        Map<String, StagedObservation> observations = readObservations(payload.get("observations"));
        Set<PreparedTarget> preparedTargets = readPreparedTargets(payload.get("preparedTargets"));

        List<ComposedFeedbackUnit> parsed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode unit : units) {
            if (parsed.size() >= MAX_UNITS) {
                log.warn("Composition stage reported more than {} units; the tail was ignored", MAX_UNITS);
                break;
            }
            ComposedFeedbackUnit composed = read(unit, observations, preparedTargets);
            if (composed == null) {
                continue;
            }
            if (!seen.add(composed.channel().name() + ':' + composed.practiceSlug())) {
                continue;
            }
            parsed.add(composed);
        }
        return List.copyOf(parsed);
    }

    public List<ComposedFeedbackUnit> parse(@Nullable JsonNode jobOutput, FeedbackChannel channel) {
        return parse(jobOutput).stream()
                .filter(unit -> unit.channel() == channel)
                .toList();
    }

    /**
     * Whether the composition was written under the contract whose review is written whole. One that was not came
     * from an earlier runner; its words for the work were fragments and nothing here may assemble them.
     */
    public boolean writtenWhole(@Nullable JsonNode jobOutput) {
        JsonNode payload = payloadOf(jobOutput);
        JsonNode version = payload == null ? null : payload.get("contractVersion");
        return version != null && version.isIntegralNumber() && version.asInt() == ComposedReview.CONTRACT_VERSION;
    }

    /**
     * The review written for the work, or null when there is none or it breaks the contract the runner enforces.
     * A malformed review is never read as silence: only a valid review that says nothing is one. A text whose support
     * the delivery gates later refuse is a different case, decided per text at admission.
     */
    public @Nullable ComposedReview review(@Nullable JsonNode jobOutput) {
        JsonNode payload = payloadOf(jobOutput);
        if (payload == null) return null;
        JsonNode review = payload.get("review");
        if (review == null || !review.isObject() || !onlyFields(review, "summary", "inline", "withheld")) {
            return null;
        }
        Map<String, StagedObservation> observations = readObservations(payload.get("observations"));
        try {
            ComposedReview.Summary summary = null;
            JsonNode summaryNode = review.get("summary");
            if (summaryNode != null && !summaryNode.isNull()) {
                summary = summaryOf(summaryNode, observations);
                if (summary == null) return invalid("summary");
            }
            List<ComposedReview.InlineNote> inline = new ArrayList<>();
            Set<String> anchors = new HashSet<>();
            for (JsonNode note : listOf(review.get("inline"), ComposedReview.MAX_INLINE_NOTES)) {
                ComposedReview.InlineNote read = inlineOf(note, observations);
                if (read == null
                        || !anchors.add(read.anchor().observationId()
                                + '#'
                                + read.anchor().citationIndex())) {
                    return invalid("line note");
                }
                inline.add(read);
            }
            List<ComposedReview.Withheld> withheld = new ArrayList<>();
            for (JsonNode decision : listOf(review.get("withheld"), Integer.MAX_VALUE)) {
                ComposedReview.Withheld read = withheldOf(decision, observations);
                if (read == null) return invalid("withholding decision");
                withheld.add(read);
            }
            Set<String> said = new HashSet<>();
            if (summary != null) said.addAll(summary.basedOn());
            inline.forEach(note -> said.addAll(note.basedOn()));
            if (withheld.stream()
                    .flatMap(decision -> decision.basedOn().stream())
                    .anyMatch(said::contains)) {
                return invalid("said and withheld observation");
            }
            return new ComposedReview(summary, inline, withheld);
        } catch (IllegalArgumentException malformed) {
            return invalid(malformed.getMessage());
        }
    }

    private static @Nullable ComposedReview invalid(@Nullable String part) {
        log.warn("The composed review breaks its contract ({}); it is not delivered", part);
        return null;
    }

    /** The items of an optional list; anything else, or more than {@code max}, is not a list this contract takes. */
    private static List<JsonNode> listOf(@Nullable JsonNode node, int max) {
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray() || node.size() > max) {
            throw new IllegalArgumentException("not a list of at most " + max);
        }
        List<JsonNode> items = new ArrayList<>(node.size());
        node.forEach(items::add);
        return items;
    }

    private static boolean onlyFields(JsonNode node, String... allowed) {
        Set<String> names = Set.of(allowed);
        return node.properties().stream().allMatch(property -> names.contains(property.getKey()));
    }

    /**
     * The next step a composition written before the review on the work had its own envelope gave each observation
     * it spoke about on the work, by observation id. Read for history only: such a composition is not delivered
     * again.
     */
    public Map<String, String> historicalNextSteps(@Nullable JsonNode jobOutput) {
        JsonNode payload = payloadOf(jobOutput);
        if (payload == null) return Map.of();
        JsonNode units = payload.get("units");
        if (units == null || !units.isArray()) {
            return Map.of();
        }
        Map<String, StagedObservation> observations = readObservations(payload.get("observations"));
        Map<String, String> nextSteps = new LinkedHashMap<>();
        for (JsonNode unit : units) {
            if (!unit.isObject() || channelOf(unit) != FeedbackChannel.IN_CONTEXT) {
                continue;
            }
            String nextStep =
                    DeveloperTextSanitizer.sanitize(text(unit, "nextStep", ComposedFeedbackUnit.MAX_NEXT_STEP_LENGTH));
            if (nextStep.isBlank()) {
                continue;
            }
            for (String id : strings(unit.get("basedOn"))) {
                if (observations.containsKey(id)) {
                    nextSteps.putIfAbsent(id, nextStep);
                }
            }
        }
        return Map.copyOf(nextSteps);
    }

    private static ComposedReview.@Nullable Summary summaryOf(
            @Nullable JsonNode node, Map<String, StagedObservation> observations) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String body = publishable(node, ComposedReview.MAX_SUMMARY_LENGTH);
        List<String> basedOn = grounded(node.get("basedOn"), observations);
        if (body == null || basedOn == null) {
            log.warn("Composed summary is empty, too long, or rests on an observation this run did not admit");
            return null;
        }
        return new ComposedReview.Summary(body, basedOn);
    }

    private static ComposedReview.@Nullable InlineNote inlineOf(
            JsonNode node, Map<String, StagedObservation> observations) {
        if (!node.isObject()) {
            return null;
        }
        String body = publishable(node, ComposedReview.MAX_INLINE_LENGTH);
        List<String> basedOn = grounded(node.get("basedOn"), observations);
        ComposedReview.ResolvedAnchor anchor = resolveAnchor(node.get("anchor"), observations);
        if (body == null || basedOn == null || anchor == null || !basedOn.contains(anchor.observationId())) {
            log.warn("Composed line note is empty, too long, ungrounded, or not placeable on its own evidence");
            return null;
        }
        return new ComposedReview.InlineNote(body, basedOn, anchor);
    }

    private static ComposedReview.@Nullable Withheld withheldOf(
            JsonNode node, Map<String, StagedObservation> observations) {
        if (!node.isObject()) {
            return null;
        }
        List<String> basedOn = grounded(node.get("basedOn"), observations);
        ComposedFeedbackUnit.WithholdReason reason = withholdReasonOf(node, "reason");
        if (basedOn == null
                || reason == null
                || basedOn.stream().anyMatch(id -> {
                    StagedObservation observation = observations.get(id);
                    return observation == null || !"NOT_MET".equals(observation.outcome());
                })) {
            log.warn("Composed withholding names no admitted observation or no reason");
            return null;
        }
        return new ComposedReview.Withheld(basedOn, reason);
    }

    /**
     * The text exactly as written, whitespace included, unless it is blank, over its bound, or carries what only the
     * server writes into a comment: an HTML comment, which is where the server's own markers live, or the tail of a
     * broken JSON envelope. Such a text is refused whole; no character of a text that is published is changed.
     */
    private static @Nullable String publishable(JsonNode node, int maxLength) {
        JsonNode value = node.get("body");
        String body = value == null || !value.isString() ? null : value.asString();
        if (body == null
                || body.isBlank()
                || body.length() > maxLength
                || body.contains("<!--")
                || !DeveloperTextSanitizer.stripEnvelopeCorruption(body).equals(body)) {
            return null;
        }
        return body;
    }

    /**
     * The ids named, when {@code basedOn} is an array of at least one string, each an observation this run admitted.
     * Nothing is dropped from it: a text whose support names anything else says something no observation backs.
     */
    private static @Nullable List<String> grounded(@Nullable JsonNode node, Map<String, StagedObservation> staged) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            if (!entry.isString()
                    || entry.asString().isBlank()
                    || !staged.containsKey(entry.asString().strip())) {
                return null;
            }
            String id = entry.asString().strip();
            StagedObservation observation = staged.get(id);
            if (observation == null) return null;
            String outcome = observation.outcome();
            if (!"MET".equals(outcome) && !"NOT_MET".equals(outcome)) return null;
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private static @Nullable ComposedFeedbackUnit read(
            JsonNode unit, Map<String, StagedObservation> observations, Set<PreparedTarget> preparedTargets) {
        if (unit == null || !unit.isObject()) {
            return null;
        }
        FeedbackChannel channel = channelOf(unit);
        if (channel == FeedbackChannel.IN_CONTEXT) {
            // A composition written before the review on the work had its own envelope; it is not delivered again.
            return null;
        }
        ComposedFeedbackUnit.Action action = actionOf(unit);
        String practiceSlug = text(unit, "practiceSlug", MAX_PRACTICE_SLUG_LENGTH);
        if (channel == null || action == null || practiceSlug == null) {
            return null;
        }
        List<String> basedOn = strings(unit.get("basedOn"));
        if (basedOn.isEmpty()) {
            return null;
        }
        String normalizedPracticeSlug = normalizeSlug(practiceSlug);
        boolean grounded = basedOn.stream().allMatch(observations::containsKey);
        boolean ownsEvidence = basedOn.stream()
                .map(observations::get)
                .filter(Objects::nonNull)
                .anyMatch(observation -> normalizedPracticeSlug.equals(observation.practiceSlug()));
        if (!grounded || !ownsEvidence) {
            log.warn(
                    "Composed unit is not grounded in admitted evidence for its practice: channel={}, practice={}",
                    channel,
                    practiceSlug);
            return null;
        }

        if (action == ComposedFeedbackUnit.Action.WITHHOLD) {
            ComposedFeedbackUnit.WithholdReason reason = withholdReasonOf(unit, "withholdReason");
            return reason == null
                    ? null
                    : new ComposedFeedbackUnit(
                            channel, normalizedPracticeSlug, basedOn, action, null, reason, null, null, null, null);
        }

        String supersedesThreadKey = null;
        if (action == ComposedFeedbackUnit.Action.SUPERSEDE) {
            supersedesThreadKey = text(unit, "supersedesThreadKey", ComposedFeedbackUnit.MAX_THREAD_KEY_LENGTH);
            if (supersedesThreadKey == null
                    || !preparedTargets.contains(
                            new PreparedTarget(supersedesThreadKey, channel, normalizedPracticeSlug))) {
                log.warn(
                        "Composed unit names a supersession target that was not staged: channel={}, practice={}",
                        channel,
                        practiceSlug);
                return null;
            }
        }

        String title = text(unit, "title", ComposedFeedbackUnit.MAX_TITLE_LENGTH);
        if (title == null) {
            return null;
        }
        if (unit.get("placement") != null && !unit.get("placement").isNull()) {
            log.warn("Composed {} unit carries a placement, which only the review on the work has", channel);
            return null;
        }

        if (channel == FeedbackChannel.IN_CHAT) {
            ComposedFeedbackUnit.ConversationBrief brief = notesOf(unit);
            return brief == null
                    ? null
                    : new ComposedFeedbackUnit(
                            channel,
                            normalizedPracticeSlug,
                            basedOn,
                            action,
                            supersedesThreadKey,
                            null,
                            title,
                            null,
                            null,
                            brief);
        }

        String nextStep =
                DeveloperTextSanitizer.sanitize(text(unit, "nextStep", ComposedFeedbackUnit.MAX_NEXT_STEP_LENGTH));
        String body = DeveloperTextSanitizer.sanitize(text(unit, "body", ComposedFeedbackUnit.MAX_BODY_LENGTH));
        ComposedFeedbackUnit composed = new ComposedFeedbackUnit(
                channel,
                normalizedPracticeSlug,
                basedOn,
                action,
                supersedesThreadKey,
                null,
                title,
                body,
                nextStep,
                null);
        return composed.isComplete() ? composed : null;
    }

    private static ComposedReview.@Nullable ResolvedAnchor resolveAnchor(
            @Nullable JsonNode anchor, Map<String, StagedObservation> observations) {
        if (anchor == null || !anchor.isObject()) {
            return null;
        }
        String observationId = text(anchor, "observationId", MAX_OBSERVATION_ID_LENGTH);
        JsonNode indexNode = anchor.get("citationIndex");
        if (observationId == null || indexNode == null || !indexNode.isIntegralNumber()) {
            return null;
        }
        StagedObservation observation = observations.get(observationId);
        if (observation == null) {
            return null;
        }
        int index = indexNode.asInt();
        if (index < 0 || index >= observation.citations().size()) {
            return null;
        }
        StagedCitation citation = observation.citations().get(index);
        if (!observation.anchorable()
                || !citation.anchorable()
                || citation.path() == null
                || citation.startLine() == null) {
            return null;
        }
        return new ComposedReview.ResolvedAnchor(
                observationId, index, citation.path(), citation.side(), citation.startLine(), citation.endLine());
    }

    private static @Nullable JsonNode payloadOf(@Nullable JsonNode jobOutput) {
        if (jobOutput == null || !jobOutput.isObject()) {
            return null;
        }
        JsonNode payload = jobOutput.get(OUTPUT_KEY);
        return payload == null || !payload.isObject() ? null : payload;
    }

    private static Map<String, StagedObservation> readObservations(@Nullable JsonNode node) {
        Map<String, StagedObservation> observations = new LinkedHashMap<>();
        if (node == null || !node.isArray()) {
            return observations;
        }
        for (JsonNode entry : node) {
            String id = text(entry, "id", MAX_OBSERVATION_ID_LENGTH);
            if (id == null) {
                continue;
            }
            List<StagedCitation> citations = new ArrayList<>();
            JsonNode citationNodes = entry.get("citations");
            if (citationNodes != null && citationNodes.isArray()) {
                for (JsonNode citation : citationNodes) {
                    citations.add(new StagedCitation(
                            text(citation, "path", 1024),
                            text(citation, "side", 8),
                            integer(citation, "startLine"),
                            integer(citation, "endLine"),
                            citation.path("anchorable").asBoolean(false)));
                }
            }
            observations.put(
                    id,
                    new StagedObservation(
                            normalizedSlug(text(entry, "practiceSlug", MAX_PRACTICE_SLUG_LENGTH)),
                            text(entry, "outcome", 32),
                            entry.path("anchorable").asBoolean(false),
                            citations));
        }
        return observations;
    }

    private static Set<PreparedTarget> readPreparedTargets(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) return Set.of();
        Set<PreparedTarget> targets = new LinkedHashSet<>();
        for (JsonNode entry : node) {
            String threadKey = text(entry, "threadKey", ComposedFeedbackUnit.MAX_THREAD_KEY_LENGTH);
            FeedbackChannel channel = channelOf(entry);
            String practiceSlug = normalizedSlug(text(entry, "practiceSlug", MAX_PRACTICE_SLUG_LENGTH));
            if (threadKey != null && channel != null && practiceSlug != null) {
                targets.add(new PreparedTarget(threadKey, channel, practiceSlug));
            }
        }
        return Set.copyOf(targets);
    }

    private static List<String> strings(@Nullable JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode entry : node) {
            if (entry.isString() && !entry.asString().strip().isEmpty()) {
                values.add(entry.asString().strip());
            }
        }
        return List.copyOf(values);
    }

    private static ComposedFeedbackUnit.@Nullable ConversationBrief notesOf(JsonNode unit) {
        JsonNode notes = unit.get("notes");
        if (notes == null || !notes.isObject()) {
            return null;
        }
        String situation = note(notes, "situation", ComposedFeedbackUnit.MAX_SITUATION_LENGTH);
        String capability = note(notes, "capability", ComposedFeedbackUnit.MAX_AIM_LENGTH);
        String evidenceSummary = note(notes, "evidenceSummary", ComposedFeedbackUnit.MAX_EVIDENCE_LENGTH);
        String inConversationSignal = note(notes, "inConversationSignal", ComposedFeedbackUnit.MAX_AIM_LENGTH);
        if (situation == null || capability == null || evidenceSummary == null || inConversationSignal == null) {
            return null;
        }
        return new ComposedFeedbackUnit.ConversationBrief(
                situation,
                capability,
                evidenceSummary,
                inConversationSignal,
                note(notes, "alreadySaid", ComposedFeedbackUnit.MAX_AIM_LENGTH));
    }

    private static @Nullable String note(JsonNode notes, String field, int maxLength) {
        String sanitized = DeveloperTextSanitizer.sanitize(text(notes, field, maxLength));
        return sanitized.isBlank() ? null : sanitized;
    }

    private static @Nullable FeedbackChannel channelOf(JsonNode unit) {
        String value = text(unit, "channel", 32);
        if (value == null) {
            return null;
        }
        for (FeedbackChannel channel : FeedbackChannel.values()) {
            if (channel.name().equalsIgnoreCase(value)) {
                return channel;
            }
        }
        return null;
    }

    private static ComposedFeedbackUnit.@Nullable Action actionOf(JsonNode unit) {
        String value = text(unit, "action", 32);
        if (value == null) {
            return null;
        }
        for (ComposedFeedbackUnit.Action action : ComposedFeedbackUnit.Action.values()) {
            if (action.name().equalsIgnoreCase(value)) {
                return action;
            }
        }
        return null;
    }

    private static ComposedFeedbackUnit.@Nullable WithholdReason withholdReasonOf(JsonNode node, String field) {
        String value = text(node, field, 32);
        if (value == null) {
            return null;
        }
        for (ComposedFeedbackUnit.WithholdReason reason : ComposedFeedbackUnit.WithholdReason.values()) {
            if (reason.name().equalsIgnoreCase(value)) {
                return reason;
            }
        }
        return null;
    }

    private static String normalizeSlug(String practiceSlug) {
        return practiceSlug.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static @Nullable String normalizedSlug(@Nullable String practiceSlug) {
        return practiceSlug == null ? null : normalizeSlug(practiceSlug);
    }

    private static @Nullable Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isIntegralNumber() ? null : value.asInt();
    }

    private static @Nullable String text(JsonNode node, String field, int maxLength) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString().strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= maxLength ? text : null;
    }

    private record StagedObservation(
            @Nullable String practiceSlug,
            @Nullable String outcome,
            boolean anchorable,
            List<StagedCitation> citations) {}

    private record PreparedTarget(String threadKey, FeedbackChannel channel, String practiceSlug) {}

    private record StagedCitation(
            @Nullable String path,
            @Nullable String side,
            @Nullable Integer startLine,
            @Nullable Integer endLine,
            boolean anchorable) {}
}
