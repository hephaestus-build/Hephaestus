package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.Js.isRecord;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.listOf;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.string;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The review on the reviewed work: what it is composed from, the {@code check_package} tool it is stored with, and the
 * checks that store or refuse it whole.
 */
final class ReviewOnWork {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    static final List<String> WITHHOLD_REASONS = List.of("NO_MATERIAL_CHANGE", "ALREADY_SAID", "BELOW_BAR");

    /** The reasons that say this work already received the advice, so each names where it was given. */
    static final Set<String> PRIOR_ADVICE_REASONS = Set.of("ALREADY_SAID", "NO_MATERIAL_CHANGE");

    static final List<String> DISPOSITIONS = List.of("RAISE", "NO_MATERIAL_CHANGE", "ALREADY_SAID", "BELOW_BAR");

    static final int SUMMARY_TARGET = 600;
    static final int INLINE_TARGET = 300;
    static final int SUMMARY_POINTS = 3;
    static final int SUMMARY_LIMIT = SUMMARY_TARGET * 2;
    static final int INLINE_LIMIT = INLINE_TARGET * 2;
    static final int INLINE_NOTES = 30;

    /** Where the server writes its own markers into a comment; a text carrying one would be read as the server's. */
    private static final String RESERVED_MARKER = "<!--";

    /** The tail a broken JSON envelope leaves on a text: {@code DeveloperTextSanitizer.ENVELOPE_TAIL}. */
    private static final Pattern ENVELOPE_TAIL = Pattern.compile("[\"'\\\\]*[}\\]][\"'\\\\]+\\s*$");

    private static final Pattern FEEDBACK_ID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", Pattern.CASE_INSENSITIVE);

    private ReviewOnWork() {}

    // ---------------------------------------------------------------- what the review rests on

    /** The admitted results the review on the work may speak about: public NOT_MET results without their digests. */
    static List<ObjectNode> publicResults(List<ObjectNode> results) {
        var reviewable = new ArrayList<ObjectNode>();
        for (ObjectNode result : results) {
            if (!isTrue(result.path("publicEligible")) || !"NOT_MET".equals(string(result.path("outcome")))) continue;
            var shown = JSON.objectNode();
            result.properties().forEach(property -> {
                if (!Set.of("citations", "example", "problems").contains(property.getKey()))
                    shown.set(property.getKey(), property.getValue());
            });
            JsonNode example = result.path("example");
            if (!example.isMissingNode()) {
                if (isRecord(example)) {
                    var copy = ((ObjectNode) example).deepCopy();
                    if (example.has("citation")) copy.set("citation", withoutDigest(example.path("citation")));
                    shown.set("example", copy);
                } else shown.set("example", example);
            }
            shown.set("citations", withoutDigests(result.path("citations")));
            var problems = shown.putArray("problems");
            for (JsonNode problem : listOf(result.path("problems"))) {
                if (isRecord(problem)) {
                    var copy = ((ObjectNode) problem).deepCopy();
                    copy.set("citations", withoutDigests(problem.path("citations")));
                    problems.add(copy);
                } else problems.add(problem);
            }
            reviewable.add(shown);
        }
        return reviewable;
    }

    static boolean isTrue(JsonNode value) {
        return value.isBoolean() && value.asBoolean();
    }

    private static JsonNode withoutDigest(JsonNode citation) {
        if (!isRecord(citation)) return citation;
        var shown = ((ObjectNode) citation).deepCopy();
        shown.remove("verification");
        return shown;
    }

    private static ArrayNode withoutDigests(JsonNode citations) {
        var shown = JSON.arrayNode();
        listOf(citations).forEach(citation -> shown.add(withoutDigest(citation)));
        return shown;
    }

    /** The native outcome-specific grounds of an abstention; generic prose is not a decided assessment. */
    static ObjectNode qualificationEvidence(JsonNode result) {
        String branch =
                "COULD_NOT_DETERMINE".equals(string(result.path("outcome"))) ? "openQuestions" : "inapplicability";
        JsonNode evidence = isRecord(result.path("evidence")) ? result.path("evidence") : JSON.objectNode();
        var shown = JSON.objectNode();
        JsonNode value = evidence.path(branch);
        if (!value.isMissingNode()) shown.set(branch, value);
        return shown;
    }

    /** The abstentions admission marked publicEligible, each as the limit it recorded with the lines it cited. */
    static List<ObjectNode> publicQualifications(List<ObjectNode> results) {
        var qualifications = new ArrayList<ObjectNode>();
        for (ObjectNode result : results) {
            String outcome = string(result.path("outcome"));
            if (!isTrue(result.path("publicEligible"))
                    || !("DOES_NOT_APPLY".equals(outcome) || "COULD_NOT_DETERMINE".equals(outcome))) continue;
            var shown = JSON.objectNode();
            shown.set("practiceSlug", result.path("practiceSlug"));
            shown.set("outcome", result.path("outcome"));
            shown.set("evidence", qualificationEvidence(result));
            shown.set("citations", withoutDigests(result.path("citations")));
            qualifications.add(shown);
        }
        return qualifications;
    }

    // ---------------------------------------------------------------- already said on this work

    /** Two cutoffs, two questions: advice the work could have answered, and words said here before composition. */
    record Witness(boolean eligibleForPriorAdvice, boolean eligibleForAlreadySaid) {}

    record OwnHistory(List<ObjectNode> feedback, int oversizedEntries, int budgetEntries) {}

    /** Kind and provider URL distinguish work without treating a display number as its identity. */
    static @Nullable String workIdentity(JsonNode kind, JsonNode url) {
        return kind.isString() && !Js.blank(kind.asString()) && url.isString() && !Js.blank(url.asString())
                ? kind.asString() + ":" + url.asString()
                : null;
    }

    static boolean deliveredBy(JsonNode deliveredAt, @Nullable String capturedAt) {
        if (!deliveredAt.isString() || capturedAt == null) return false;
        Instant delivered = Js.instant(deliveredAt.asString());
        Instant captured = Js.instant(capturedAt);
        return delivered != null && captured != null && !delivered.isAfter(captured);
    }

    private static ArrayNode postedNotes(JsonNode copies) {
        var notes = JSON.arrayNode();
        for (JsonNode copy : listOf(copies)) {
            String kind = string(copy.path("kind"));
            if (isRecord(copy)
                    && ("LINE_NOTE".equals(kind) || "FILE_NOTE".equals(kind))
                    && "POSTED".equals(string(copy.path("state")))
                    && copy.path("body").isString()
                    && !Js.blank(copy.path("body").asString())) {
                var note = JSON.objectNode();
                note.put("kind", kind);
                putDefined(note, "path", copy.path("path"));
                putDefined(note, "startLine", copy.path("startLine"));
                putDefined(note, "endLine", copy.path("endLine"));
                putDefined(note, "side", copy.path("side"));
                note.put("body", copy.path("body").asString());
                notes.add(note);
            }
        }
        return notes;
    }

    /** {@code JSON.stringify} drops a property whose value is undefined. */
    static void putDefined(ObjectNode node, String name, JsonNode value) {
        if (!value.isMissingNode()) node.set(name, value);
    }

    /**
     * The history a public history read returned, as the review reads it: each entry about the read's item, eligible
     * for the review on the work, with its claim's currentness, and its words marked unavailable when the read had no
     * authority to copy them.
     */
    static ObjectNode publicHistory(JsonNode read) {
        JsonNode history = read.path("history");
        var shown = history.isObject() ? ((ObjectNode) history).deepCopy() : JSON.objectNode();
        var feedback = JSON.arrayNode();
        for (JsonNode entry : listOf(history.path("feedback"))) {
            var copy = entry.isObject() ? ((ObjectNode) entry).deepCopy() : JSON.objectNode();
            copy.set("item", history.path("item"));
            copy.put("publicEligible", true);
            copy.set("recordedClaimCurrentness", entry.path("currentness"));
            if (isTrue(entry.path("bodyUnavailable"))) copy.put("wordsUnavailable", "COPY_AUTHORITY_UNAVAILABLE");
            feedback.add(copy);
        }
        shown.set("feedback", feedback);
        return shown;
    }

    /**
     * What was already said on this very piece of work, from the feedback history: only the review on the work, never
     * what reached the developer's own pages or conversations. An entry without an id, a time or delivered words is
     * context, never prior advice.
     */
    static OwnHistory priorPublicFeedback(
            JsonNode history, @Nullable String thisWork, @Nullable String capturedAt, @Nullable String readAt) {
        if (thisWork == null || !isRecord(history) || !history.path("feedback").isArray())
            return new OwnHistory(List.of(), 0, 0);
        var projected = new ArrayList<ObjectNode>();
        for (JsonNode entry : history.path("feedback")) {
            if (!isRecord(entry)
                    || !"ON_WORK".equals(string(entry.path("destination")))
                    || !isTrue(entry.path("publicEligible"))) continue;
            JsonNode item = isRecord(entry.path("item")) ? entry.path("item") : JSON.objectNode();
            if (!thisWork.equals(workIdentity(item.path("kind"), item.path("url")))) continue;
            JsonNode id = entry.path("id");
            String witnessId =
                    id.isString() && FEEDBACK_ID.matcher(id.asString()).matches() ? "feedback:" + id.asString() : null;
            var notes = postedNotes(entry.path("copies"));
            JsonNode body = entry.path("body");
            String currentness = string(entry.path("recordedClaimCurrentness"));
            boolean recorded = witnessId != null
                    && ((body.isString() && !Js.blank(body.asString())) || !notes.isEmpty())
                    && ("CURRENT".equals(currentness) || "STALE".equals(currentness))
                    && !isTrue(entry.path("withdrawn"));
            var own = JSON.objectNode();
            own.put("witnessId", witnessId);
            putDefined(own, "id", id);
            putDefined(own, "itemVersion", entry.path("itemVersion"));
            putDefined(own, "basedOn", entry.path("basedOn"));
            putDefined(own, "deliveredAt", entry.path("deliveredAt"));
            putDefined(own, "body", body);
            if (!notes.isEmpty()) own.set("notes", notes);
            putDefined(own, "recordedClaimCurrentness", entry.path("recordedClaimCurrentness"));
            putDefined(own, "withdrawn", entry.path("withdrawn"));
            if ("COPY_AUTHORITY_UNAVAILABLE".equals(string(entry.path("wordsUnavailable"))))
                own.put("wordsUnavailable", "COPY_AUTHORITY_UNAVAILABLE");
            own.put(
                    "eligibleForPriorAdvice",
                    recorded && "CURRENT".equals(currentness) && deliveredBy(entry.path("deliveredAt"), capturedAt));
            own.put("eligibleForAlreadySaid", recorded && deliveredBy(entry.path("deliveredAt"), readAt));
            projected.add(own);
        }
        var feedback = new ArrayList<ObjectNode>();
        int oversized = 0;
        int budget = 0;
        for (var entry : projected) {
            var candidate = new ArrayList<>(feedback);
            candidate.add(entry);
            if (JsonText.stringify(entry, 1).length() > CapturedWork.SAME_WORK_SOURCE_CHARS) oversized++;
            else if (ownHistoryText(candidate, projected.size(), projected.size(), null)
                            .length()
                    > CapturedWork.SAME_WORK_TOTAL_CHARS) budget++;
            else feedback.add(entry);
        }
        return new OwnHistory(feedback, oversized, budget);
    }

    private static String ownHistoryText(
            List<ObjectNode> feedback, int oversizedEntries, int budgetEntries, @Nullable String readAt) {
        String asRead = readAt == null ? "" : ", as read at " + readAt;
        String shown;
        if (feedback.isEmpty()) shown = "No same-work delivered feedback is shown here" + asRead + ".\n";
        else {
            var wrapper = JSON.objectNode();
            var list = wrapper.putArray("alreadySaid");
            feedback.forEach(list::add);
            shown = "What Hephaestus delivered on this work" + asRead + ":\n```json\n" + JsonText.stringify(wrapper, 1)
                    + "\n```\n";
        }
        String omitted = oversizedEntries > 0 || budgetEntries > 0
                ? "Prior feedback omitted whole: " + oversizedEntries + " entries exceed the per-entry limit; "
                        + budgetEntries
                        + " exceed the total history limit. Their contents are not shown and cannot be prior-advice witnesses.\n"
                : "";
        return shown + omitted;
    }

    static Map<String, Witness> priorAdviceWitnesses(List<ObjectNode> own, JsonNode capturedStatements) {
        var witnesses = new LinkedHashMap<String, Witness>();
        for (var statement : own) {
            String witnessId = string(statement.path("witnessId"));
            if (witnessId != null)
                witnesses.put(
                        witnessId,
                        new Witness(
                                isTrue(statement.path("eligibleForPriorAdvice")),
                                isTrue(statement.path("eligibleForAlreadySaid"))));
        }
        for (JsonNode statement : listOf(capturedStatements)) {
            boolean eligible = isTrue(statement.path("eligibleForPriorAdvice"));
            witnesses.put(Evidence.text(statement.path("witnessId")), new Witness(eligible, eligible));
        }
        return witnesses;
    }

    // ---------------------------------------------------------------- the tool

    /** What check_package tells the model it does. The rules are applied by {@link #read}, with every reason at once. */
    static final String TOOL_DESCRIPTION =
            "Check a complete draft without publishing it. Correct any refusal before publishing its returned draftId. First decide each NOT_MET result: RAISE it, or withhold it "
                    + "with your reason and, for advice this work already received, the statement that gave it. Write useful local notes first, "
                    + "then use the summary for a warranted point without a useful line note, or useful orientation. Do not repeat a note's diagnosis or action. "
                    + "Use summary:null when it adds nothing; without useful line notes, put the complete guidance in the summary. "
                    + "Together the texts speak about every raised result, itself or through "
                    + "its problems, and no withheld one. Each body is published whole as written, with provider safety formatting and a fixed disclosure; nothing is "
                    + "assembled from fragments. An accepted call is final and ends the composition. A missing or contradicted decision, "
                    + "invalid support, eligibility or location, or a witness that cannot stand as the advice refuses the whole "
                    + "review, with every reason, so it can be corrected and sent again. "
                    + "Aim for at most " + SUMMARY_TARGET + " characters and " + SUMMARY_POINTS
                    + " points in the summary, "
                    + INLINE_TARGET
                    + " characters and one local point per line note. Each point uses at most two short sentences: what, then why or next action. "
                    + "No headings, internal labels or citation coordinates in the published text; refer to evidence in natural words or a short exact quote. "
                    + "A summary over " + SUMMARY_LIMIT + " characters or a note over " + INLINE_LIMIT
                    + " is refused for correction, never truncated. "
                    + "Input example with one useful line note: {\"decisions\":[{\"resultId\":\"<admitted NOT_MET resultId>\",\"disposition\":\"RAISE\"}],\"inline\":[{\"basedOn\":[\"<its problemId>\"],\"body\":\"Explain the local concern and its remedy.\",\"anchor\":{\"problemId\":\"<same problemId>\",\"citationIndex\":0}}],\"summary\":null}. "
                    + "For a quiet review, use summary:null, inline:[] and a supported withholding decision for each result. Unused inline and witnessIds lists may be null or omitted.";

    /** How the review is written. It ends the opening turn, after the criteria. */
    static final String WRITE_CONTRACT = "## Writing the review\n"
            + "Write from the results and problems each text rests on, within their qualifications. A declared affordance supports its bounded "
            + "benefit, not an unobserved runtime or test outcome. Keep each remedy on its recorded gap and leave unrelated "
            + "behavior as it is; make another change a prerequisite only when the evidence establishes that dependency. "
            + "You are not told whether the work is ready, so do not approve it, call it ready or blocked, or set conditions "
            + "for merging it. Timing the evidence itself warrants is fine: a committed secret is removed and rotated before "
            + "anyone relies on the history.\n\n"
            + "Aim for at most " + SUMMARY_TARGET + " characters in the whole summary, with at most " + SUMMARY_POINTS
            + " points, "
            + "and " + INLINE_TARGET
            + " characters per line note. Each point uses at most two short sentences: what is wrong, "
            + "then why it matters or what to do next. A small point needs a short explanation. Keep the evidence and its qualifications; "
            + "cut repetition and withhold lesser concerns rather than dropping a material limit to fit. "
            + "Write plain prose or a short list without headings or internal labels such as Situation:, Capability: or Evidence:. "
            + "Say ‘the description’s closing line’ or use a short exact quote, not ‘description L2’, ‘description L16’ or citation indices. "
            + "Use basedOn and anchors to identify the exact evidence; leave its coordinates in the source evidence.\n\n"
            + "Make each point fully once in this review. Write useful line notes before deciding what the summary still needs:\n"
            + "- A line note is one self-contained point about the code at its anchor: that local concern with its action and "
            + "evidence. It is read alone, so it says its point completely. Several practices "
            + "may support it when they describe that one event; it does not collect the review's other points.\n"
            + "- The summary orients the reader: the guidance its raised NOT_MET results warrant, most important first. It "
            + "carries every ask that is not "
            + "about one place in the code, such as the description, a reply to a reviewer or a work-wide change, unless it "
            + "forms one point with the code at a line. When a point has no useful line note, the summary carries that point "
            + "completely. It may name or locate a line note's topic, and then names that note's "
            + "results or problems in basedOn, but it does not restate the note's diagnosis or action. "
            + "Use summary:null when the notes carry every ask and an overview adds no useful orientation.\n"
            + "- The review speaks only about NOT_MET results and their problems. It never acknowledges or praises a MET "
            + "result, and it does not use praise to cushion a concern. When no NOT_MET result is raised, the review says "
            + "nothing: the summary is null and there are no line notes.\n\n"
            + "One result or problem may support notes at several places, and also the summary's overview of them.";

    static final String STANDARD_REFERENCE =
            "Reference, whole as staged: each standard explains what its practice's recorded results were assessed "
                    + "against and the responses it accepts, and qualifies whether and how those results and their problems may "
                    + "be communicated. It is not a request to assess the work again and raises no concern of its own.";

    /** Offered before the body to orient generation; field order is not enforced. */
    private static final String SUPPORT_FIRST =
            "Choose these before writing the body: the id of every NOT_MET result or problem behind a concern this body "
                    + "states, and no other: a result's resultId, or a problem's problemId for one located problem. "
                    + "One named by another body does not support this one. Then write the body from exactly these.";

    /** The nudge a composer gets when it ends without the final call. */
    static final String NUDGE =
            "Everything this review may rest on is in this session. Check the complete draft now with check_package, then publish_package using the returned draftId: "
                    + "first one decision for each NOT_MET result, RAISE or a withholding reason, then the complete summary and "
                    + "any line notes, which speak about each raised result, itself or through its problems, and no withheld one. "
                    + "No prose outside the calls.";

    private static ObjectNode idList(List<String> ids, String description) {
        var list = JSON.objectNode();
        list.put("type", "array");
        list.put("minItems", 1);
        var items = list.putObject("items");
        items.put("type", "string");
        if (!ids.isEmpty()) {
            var choices = items.putArray("enum");
            ids.forEach(choices::add);
        }
        list.put("description", description);
        return list;
    }

    /** The JSON schema of check_package's arguments for these results. */
    static ObjectNode toolParameters(
            Map<String, Evidence.Result> results, boolean lineNotes, List<String> eligibleWitnesses) {
        var concerns = results.entrySet().stream()
                .filter(entry -> entry.getValue().notMet())
                .toList();
        if (concerns.isEmpty())
            throw new IllegalArgumentException("check_package needs at least one NOT_MET result to rest on");
        var notMet = concerns.stream().map(Map.Entry::getKey).toList();
        var restable = new ArrayList<String>();
        var anchorable = new ArrayList<String>();
        for (var concern : concerns) {
            restable.add(concern.getKey());
            for (var problem : concern.getValue().problems()) {
                restable.add(problem.problemId());
                if (lineNotes && problem.citations().stream().anyMatch(citation -> isTrue(citation.path("anchorable"))))
                    anchorable.add(problem.problemId());
            }
        }
        var parameters = JSON.objectNode();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.putArray("required").add("decisions").add("summary");
        var properties = parameters.putObject("properties");

        var decisions = properties.putObject("decisions");
        decisions.put("type", "array");
        decisions.put("minItems", notMet.size());
        decisions.put("maxItems", notMet.size());
        decisions.put(
                "description",
                "Decided first: one decision for each NOT_MET result. RAISE means the "
                        + "texts below speak about it or its problems; any other disposition withholds it, and no text "
                        + "speaks about it or its problems.");
        var decision = decisions.putObject("items");
        decision.put("type", "object");
        decision.put("additionalProperties", false);
        decision.putArray("required").add("resultId").add("disposition");
        var decisionProperties = decision.putObject("properties");
        var resultId = decisionProperties.putObject("resultId");
        resultId.put("type", "string");
        var resultIds = resultId.putArray("enum");
        notMet.forEach(resultIds::add);
        var disposition = decisionProperties.putObject("disposition");
        disposition.put("type", "string");
        var dispositions = disposition.putArray("enum");
        DISPOSITIONS.forEach(dispositions::add);
        var witnessIds = decisionProperties.putObject("witnessIds");
        witnessIds.put("type", "array");
        if (!eligibleWitnesses.isEmpty()) {
            var items = witnessIds.putObject("items");
            items.put("type", "string");
            var choices = items.putArray("enum");
            eligibleWitnesses.forEach(choices::add);
        } else {
            witnessIds.put("maxItems", 0);
            witnessIds.putObject("items").put("type", "string");
        }
        witnessIds.put(
                "description",
                "For ALREADY_SAID or NO_MATERIAL_CHANGE: the witnessId of each statement under what was "
                        + "already said on this work that gave this advice. ALREADY_SAID takes a statement marked "
                        + "eligibleForAlreadySaid; NO_MATERIAL_CHANGE one marked eligibleForPriorAdvice. Checked, "
                        + "never stored.");

        var inline = properties.putObject("inline");
        inline.put("type", "array");
        inline.put("maxItems", anchorable.isEmpty() ? 0 : INLINE_NOTES);
        inline.put(
                "description",
                "Notes intended for cited lines of the change, each one self-contained point about the code at "
                        + "its line. Each location can fail independently.");
        var note = inline.putObject("items");
        note.put("type", "object");
        note.put("additionalProperties", false);
        note.putArray("required").add("basedOn").add("body").add("anchor");
        var noteProperties = note.putObject("properties");
        noteProperties.set(
                "basedOn",
                idList(restable, SUPPORT_FIRST + " It includes the anchor's problem or that problem's result."));
        var noteBody = noteProperties.putObject("body");
        noteBody.put("type", "string");
        noteBody.put("minLength", 1);
        noteBody.put("maxLength", INLINE_LIMIT);
        noteBody.put(
                "description",
                "The whole note in Markdown, from the results and problems in basedOn. Aim for at most " + INLINE_TARGET
                        + " characters, "
                        + "one point about this line, at most two short sentences: what, then why or next action. No headings, internal labels or citation coordinates.");
        var anchor = noteProperties.putObject("anchor");
        anchor.put("type", "object");
        anchor.put("additionalProperties", false);
        anchor.putArray("required").add("problemId").add("citationIndex");
        anchor.put(
                "description",
                "The line, as one citation marked anchorable of a problem that basedOn names, itself or "
                        + "through its result. A result's own citations take no note.");
        var anchorProperties = anchor.putObject("properties");
        var problemId = anchorProperties.putObject("problemId");
        problemId.put("type", "string");
        if (!anchorable.isEmpty()) {
            var choices = problemId.putArray("enum");
            anchorable.forEach(choices::add);
        }
        var citationIndex = anchorProperties.putObject("citationIndex");
        citationIndex.put("type", "integer");
        citationIndex.put("minimum", 0);

        var summary = properties.putObject("summary");
        summary.putArray("type").add("object").add("null");
        summary.put("additionalProperties", false);
        summary.putArray("required").add("basedOn").add("body");
        summary.put(
                "description",
                "Write after the useful line notes: guidance without a useful line note, or brief orientation, without "
                        + "repeating their diagnosis or action. With no useful line notes, carry the complete guidance here. "
                        + "It never acknowledges a MET result. Send null when it adds no useful comment of its own.");
        var summaryProperties = summary.putObject("properties");
        summaryProperties.set(
                "basedOn",
                idList(restable, SUPPORT_FIRST + " If any of them may not go out, the whole comment stays unsaid."));
        var summaryBody = summaryProperties.putObject("body");
        summaryBody.put("type", "string");
        summaryBody.put("minLength", 1);
        summaryBody.put("maxLength", SUMMARY_LIMIT);
        summaryBody.put(
                "description",
                "The whole comment in Markdown, from the results and problems in basedOn. Aim for at most "
                        + SUMMARY_TARGET
                        + " characters "
                        + "and " + SUMMARY_POINTS
                        + " points; at most two short sentences per point: what, then why or next action. No headings, internal labels or citation coordinates.");
        return parameters;
    }

    // ---------------------------------------------------------------- the turn

    /** One practice as the review sees it: its name, why it matters, its known limits and its version. */
    record Practice(
            String slug,
            String name,
            @Nullable String whyItMatters,
            List<String> knownLimitations,
            @Nullable String versionId) {}

    /** Everything the review on the work is composed from; nothing else reaches its session. */
    record TurnInput(
            String sameWork,
            List<ObjectNode> results,
            List<ObjectNode> qualifications,
            OwnHistory alreadySaid,
            @Nullable String ownHistoryReadAt,
            ObjectNode captured,
            List<Practice> practices,
            List<String> notReached,
            boolean lineNotes,
            Function<String, @Nullable String> stagedCriteria) {}

    static String turn(TurnInput input) {
        String own = ownHistoryText(
                input.alreadySaid().feedback(),
                input.alreadySaid().oversizedEntries(),
                input.alreadySaid().budgetEntries(),
                input.ownHistoryReadAt());
        String others =
                input.captured().path("sources").isEmpty() && !input.captured().has("omitted")
                        ? "What people and tools said on this work was not part of this capture, so it is unknown.\n"
                        : "Captured public discussion on this work; omitted sources remain unknown:\n```json\n"
                                + JsonText.stringify(input.captured(), 1) + "\n```\n";
        String said = own + others
                + "An `ALREADY_SAID` decision names the `witnessId` of a statement marked `eligibleForAlreadySaid`; a `NO_MATERIAL_CHANGE` decision one marked `eligibleForPriorAdvice`; any other statement is context only. `eligibleForAlreadySaid` alone establishes communication by the read time, not that the captured work received or responded to it.\n";
        String qualifications = "";
        if (!input.qualifications().isEmpty()) {
            var wrapper = JSON.objectNode();
            var list = wrapper.putArray("results");
            input.qualifications().forEach(list::add);
            qualifications = "### Recorded limits of this review\n"
                    + "Practices this review looked at and did not decide, or found not applicable, each with the limit it recorded and the lines it cited. They bound what the review may claim. They are not concerns: no text rests on them, and nothing is decided about them here.\n"
                    + "```json\n" + JsonText.stringify(wrapper, 1) + "\n```\n";
        }
        String location = input.lineNotes()
                ? "- Line notes: a note's `anchor` names one citation marked `anchorable` of a problem, by the "
                        + "problem's `problemId` and the citation's `citationIndex`; a result's own citations take no note. "
                        + "On GitHub it is a review comment on that line; on GitLab it is a positioned discussion on the reviewed diff. "
                        + "If a diff position is unavailable or explicitly rejected, delivery can use a file note with a link to the reviewed code. "
                        + "Each body stands on its own, and each location can fail independently.\n"
                : "- This work has no lines a note can sit on; everything goes in the summary.\n";
        JsonNode itemVersion = input.captured().path("itemVersion");
        String concerns = notMetReference(
                input.results(),
                input.stagedCriteria(),
                input.practices(),
                input.alreadySaid().feedback(),
                itemVersion.isString() ? itemVersion.asString() : null);
        return "## The review to write\n"
                + "The measurement of this work is finished. The captured work establishes its purpose and evidence. Earlier public communication records what was said, not current verification. Each concern below carries its recorded grounds and standard.\n\n"
                + "### The reviewed work, as captured\n" + input.sameWork() + "\n"
                + "### Already said on this work\n" + said + "\n"
                + "### Concerns to decide\n"
                + "Candidate prior witness references associate captured practice metadata only. Read their complete statements above to judge whether the advice matches, remains warranted, or is worth repeating; a missing association does not establish novelty.\n"
                + concerns + "\n"
                + qualifications + "\n"
                + "### Where the words go\n"
                + "- The summary: one comment on the work.\n"
                + location + "\n"
                + Evidence.notReachedNote(input.notReached())
                + "Check the whole review with check_package, then publish_package using the returned draftId. It decides first: each NOT_MET result is RAISE, and a text speaks about it or its problems, or withheld with its reason, and no text speaks about it or its problems. Writing nothing for the work is a decision too: it is still a checked draft followed by publish_package.\n\n"
                + WRITE_CONTRACT;
    }

    /** Each concern's complete grounds, limits, standard and candidate communication references, by practice. */
    static String notMetReference(
            List<ObjectNode> reviewable,
            Function<String, @Nullable String> stagedCriteria,
            List<Practice> practices,
            List<ObjectNode> history,
            @Nullable String itemVersion) {
        var slugs = new LinkedHashSet<String>();
        for (var result : reviewable)
            if (isTrue(result.path("publicEligible")) && "NOT_MET".equals(string(result.path("outcome"))))
                slugs.add(Evidence.text(result.path("practiceSlug")));
        if (slugs.isEmpty()) return "No public NOT_MET result was admitted.\n";
        var concerns = new ArrayList<String>();
        for (String slug : slugs) {
            var results = reviewable.stream()
                    .filter(entry -> isTrue(entry.path("publicEligible"))
                            && "NOT_MET".equals(string(entry.path("outcome")))
                            && slug.equals(string(entry.path("practiceSlug"))))
                    .toList();
            concerns.add(practiceStandard(slug, stagedCriteria)
                    + practiceReference(slug, results, practices, history, itemVersion));
        }
        return STANDARD_REFERENCE + "\n" + String.join("\n", concerns);
    }

    /** Association by captured practice metadata only: these references decide neither a match nor novelty. */
    private static String practiceReference(
            String slug,
            List<ObjectNode> results,
            List<Practice> practices,
            List<ObjectNode> history,
            @Nullable String itemVersion) {
        var practice = practices.stream()
                .filter(entry -> entry.slug().equals(slug))
                .findFirst()
                .orElse(null);
        var candidates = JSON.arrayNode();
        for (var entry : history) {
            JsonNode basedOn = entry.path("basedOn");
            boolean alreadySaid = isTrue(entry.path("eligibleForAlreadySaid"));
            boolean priorAdvice = isTrue(entry.path("eligibleForPriorAdvice"));
            if (entry.path("witnessId").isNull()
                    || (!alreadySaid && !priorAdvice)
                    || !basedOn.isArray()
                    || listOf(basedOn).stream()
                            .noneMatch(
                                    support -> isRecord(support) && slug.equals(string(support.path("practiceSlug")))))
                continue;
            var candidate = JSON.objectNode();
            candidate.set("witnessId", entry.path("witnessId"));
            JsonNode version = entry.path("itemVersion");
            if (version.isString()) candidate.put("itemVersion", version.asString());
            else candidate.putNull("itemVersion");
            var supports = candidate.putArray("basedOn");
            listOf(basedOn).stream()
                    .filter(support -> isRecord(support) && slug.equals(string(support.path("practiceSlug"))))
                    .forEach(supports::add);
            candidate.put("eligibleForAlreadySaid", alreadySaid);
            candidate.put("eligibleForPriorAdvice", priorAdvice);
            candidates.add(candidate);
        }
        var reference = JSON.objectNode();
        reference.put("itemVersion", itemVersion);
        if (practice != null) {
            var shown = reference.putObject("practice");
            shown.put("slug", practice.slug());
            shown.put("name", practice.name());
            if (practice.versionId() != null) shown.put("versionId", practice.versionId());
            var limitations = shown.putArray("knownLimitations");
            practice.knownLimitations().forEach(limitations::add);
        }
        var list = reference.putArray("results");
        results.forEach(list::add);
        reference.set("candidatePriorWitnesses", candidates);
        return "```json\n" + JsonText.stringify(reference, 1) + "\n```\n";
    }

    /** One practice's staged criteria, whole and fenced so their own code blocks cannot close it; never cut. */
    static String practiceStandard(String slug, Function<String, @Nullable String> stagedCriteria) {
        String criteria;
        try {
            criteria = stagedCriteria.apply(slug);
        } catch (RuntimeException unreadable) {
            return "### Criteria of `" + slug + "` — could not be read; nothing is known about them here\n";
        }
        if (criteria == null)
            return "### Criteria of `" + slug + "` — not available for this review; nothing is known about them here\n";
        if (criteria.isEmpty()) return "### Criteria of `" + slug + "` — staged empty; no standard is available here\n";
        String fence = "`".repeat(Math.max(3, CapturedWork.longestBacktickRun(criteria) + 1));
        return "### Criteria of `" + slug + "`\n" + fence + "markdown\n" + criteria + "\n" + fence + "\n";
    }

    // ---------------------------------------------------------------- reading the review

    record Summary(String body, List<Evidence.Ref> basedOn) {}

    record Anchor(String problemId, int citationIndex) {}

    record InlineNote(String body, List<Evidence.Ref> basedOn, Anchor anchor) {}

    record Withheld(List<Evidence.Ref> basedOn, String reason, List<String> witnessIds) {}

    /** What the reviewed work is told: one summary, the notes it places on lines, and what it leaves unsaid. */
    record ComposedReview(@Nullable Summary summary, List<InlineNote> inline, List<Withheld> withheld) {
        Set<String> decided() {
            var decided = new LinkedHashSet<String>();
            if (summary != null) summary.basedOn().forEach(ref -> decided.add(ref.resultId()));
            inline.forEach(note -> note.basedOn().forEach(ref -> decided.add(ref.resultId())));
            withheld.forEach(decision -> decision.basedOn().forEach(ref -> decided.add(ref.resultId())));
            return decided;
        }
    }

    /** The stored review, or every reason it was refused. */
    record Read(@Nullable ComposedReview review, List<String> errors) {}

    /** A transport envelope carries no result fields of its own. Unwrap once; never discard sibling fields. */
    static JsonNode unwrapArguments(JsonNode value) {
        if (!isRecord(value) || value.size() != 1) return value;
        String key = value.propertyNames().iterator().next();
        return Set.of("args", "arguments", "input").contains(key) && isRecord(value.path(key))
                ? value.path(key)
                : value;
    }

    private record DecisionInput(String resultId, String disposition, List<String> witnessIds) {}

    private record NoteInput(String body, List<String> basedOn, String problemId, int citationIndex) {}

    /** Stores the review whole: a refused part cannot be dropped while retaining decisions written with it. */
    static Read read(
            JsonNode raw, Map<String, Evidence.Result> results, boolean lineNotes, Map<String, Witness> witnesses) {
        JsonNode value = unwrapArguments(raw);
        var errors = new ArrayList<String>();
        if (!isRecord(value))
            return new Read(null, List.of("review: expected an object with decisions, summary and inline"));
        for (String key : value.propertyNames())
            if (!Set.of("decisions", "summary", "inline").contains(key))
                errors.add("review: unrecognized key \"" + key + "\"");
        var decisionInputs = new ArrayList<DecisionInput>();
        JsonNode decisionsNode = value.path("decisions");
        if (!decisionsNode.isArray()) errors.add("review.decisions: expected an array of decisions");
        else
            for (int index = 0; index < decisionsNode.size(); index++) {
                JsonNode decision = decisionsNode.get(index);
                String prefix = "review.decisions." + index;
                if (!isRecord(decision)) {
                    errors.add(prefix + ": expected an object with resultId, disposition and witnessIds");
                    continue;
                }
                for (String key : decision.propertyNames())
                    if (!Set.of("resultId", "disposition", "witnessIds").contains(key))
                        errors.add(prefix + ": unrecognized key \"" + key + "\"");
                String id = string(decision.path("resultId"));
                String disposition = string(decision.path("disposition"));
                if (id == null || Js.blank(id)) errors.add(prefix + ".resultId: expected a non-empty string");
                if (disposition == null || !DISPOSITIONS.contains(disposition))
                    errors.add(prefix + ".disposition: expected one of " + String.join(", ", DISPOSITIONS));
                List<String> witnessIds = List.of();
                JsonNode witnessNode = decision.path("witnessIds");
                if (!witnessNode.isMissingNode() && !witnessNode.isNull()) {
                    witnessIds = ids(witnessNode);
                    if (witnessIds == null) {
                        errors.add(prefix + ".witnessIds: expected an array of non-empty strings");
                        witnessIds = List.of();
                    }
                }
                if (id != null && !Js.blank(id) && disposition != null && DISPOSITIONS.contains(disposition))
                    decisionInputs.add(new DecisionInput(Js.trim(id), disposition, witnessIds));
            }
        JsonNode summaryNode = value.path("summary");
        String summaryBody = null;
        List<String> summaryBasedOn = null;
        boolean hasSummary = !summaryNode.isMissingNode() && !summaryNode.isNull();
        if (hasSummary) {
            if (!isRecord(summaryNode)) errors.add("review.summary: expected an object with body and basedOn, or null");
            else {
                for (String key : summaryNode.propertyNames())
                    if (!Set.of("body", "basedOn").contains(key))
                        errors.add("review.summary: unrecognized key \"" + key + "\"");
                summaryBody = string(summaryNode.path("body"));
                if (summaryBody == null) errors.add("review.summary.body: expected a string");
                summaryBasedOn = ids(summaryNode.path("basedOn"));
                if (summaryBasedOn == null)
                    errors.add("review.summary.basedOn: expected an array of non-empty strings");
            }
        }
        var noteInputs = new ArrayList<NoteInput>();
        JsonNode inlineNode = value.path("inline");
        if (!inlineNode.isMissingNode() && !inlineNode.isNull()) {
            if (!inlineNode.isArray()) errors.add("review.inline: expected an array of notes, or null");
            else
                for (int index = 0; index < inlineNode.size(); index++) {
                    JsonNode note = inlineNode.get(index);
                    String prefix = "review.inline." + index;
                    if (!isRecord(note)) {
                        errors.add(prefix + ": expected an object with body, basedOn and anchor");
                        continue;
                    }
                    for (String key : note.propertyNames())
                        if (!Set.of("body", "basedOn", "anchor").contains(key))
                            errors.add(prefix + ": unrecognized key \"" + key + "\"");
                    String body = string(note.path("body"));
                    List<String> basedOn = ids(note.path("basedOn"));
                    JsonNode anchor = note.path("anchor");
                    String problemId = string(anchor.path("problemId"));
                    Integer citationIndex = citationIndex(anchor.path("citationIndex"));
                    if (body == null) errors.add(prefix + ".body: expected a string");
                    if (basedOn == null) errors.add(prefix + ".basedOn: expected an array of non-empty strings");
                    if (!isRecord(anchor))
                        errors.add(prefix + ".anchor: expected an object with problemId and citationIndex");
                    else {
                        for (String key : anchor.propertyNames())
                            if (!Set.of("problemId", "citationIndex").contains(key))
                                errors.add(prefix + ".anchor: unrecognized key \"" + key + "\"");
                        if (problemId == null || Js.blank(problemId))
                            errors.add(prefix + ".anchor.problemId: expected a non-empty string");
                        if (citationIndex == null)
                            errors.add(prefix + ".anchor.citationIndex: expected a non-negative integer");
                    }
                    if (body != null
                            && basedOn != null
                            && problemId != null
                            && !Js.blank(problemId)
                            && citationIndex != null)
                        noteInputs.add(new NoteInput(body, basedOn, Js.trim(problemId), citationIndex));
                }
        }
        if (!errors.isEmpty()) return new Read(null, errors);

        var named = Evidence.named(results);
        var decided = new LinkedHashMap<String, String>();
        var decidedNamed = new LinkedHashSet<String>();
        for (int index = 0; index < decisionInputs.size(); index++) {
            var decision = decisionInputs.get(index);
            String label = "decisions #" + (index + 1);
            var problems = new ArrayList<String>();
            var result = results.get(decision.resultId());
            var parent = named.get(decision.resultId());
            if (result == null)
                problems.add(
                        parent == null
                                ? decision.resultId() + " is not one of the results this review may rest on"
                                : decision.resultId() + " is a problem of "
                                        + parent.ref().resultId() + "; a decision names its result's resultId");
            else if (!result.notMet())
                problems.add(
                        decision.resultId() + " is not a NOT_MET result; only a result with problems takes a decision");
            else if (decidedNamed.contains(decision.resultId()))
                problems.add(decision.resultId() + " already has a decision; each NOT_MET result takes one");
            if (result != null && result.notMet()) decidedNamed.add(decision.resultId());
            if (decision.disposition().equals("RAISE")) {
                if (!decision.witnessIds().isEmpty()) problems.add("a raised result names no witness");
            } else problems.addAll(witnessProblems(decision.disposition(), decision.witnessIds(), witnesses));
            problems.forEach(problem -> errors.add(label + ": " + problem));
            if (problems.isEmpty()) decided.put(decision.resultId(), decision.disposition());
        }

        Summary summary = null;
        if (summaryBody != null && summaryBasedOn != null) {
            var problems = new ArrayList<String>();
            String text = textProblem(
                    summaryBody,
                    SUMMARY_TARGET,
                    "The description names the change but omits its purpose. Add the reason so reviewers can judge the scope.");
            if (text != null) problems.add(text);
            String support = supportProblem(summaryBasedOn, named);
            if (support != null) problems.add(support);
            if (!problems.isEmpty()) problems.forEach(problem -> errors.add("summary: " + problem));
            else summary = new Summary(summaryBody, Evidence.refs(summaryBasedOn, named));
        }

        if (noteInputs.size() > INLINE_NOTES)
            errors.add("inline: at most " + INLINE_NOTES + " line notes; this review has " + noteInputs.size());
        if (!noteInputs.isEmpty() && !lineNotes)
            errors.add("inline: this work has no lines to place a note on; say it in the summary");
        var inline = new ArrayList<InlineNote>();
        var anchors = new HashSet<String>();
        for (int index = 0; index < noteInputs.size(); index++) {
            var note = noteInputs.get(index);
            String label = "inline #" + (index + 1);
            var problems = new ArrayList<String>();
            String text = textProblem(
                    note.body(),
                    INLINE_TARGET,
                    "This branch uses the token without a check. Validate it before use so an invalid token cannot reach the request.");
            if (text != null) problems.add(text);
            String support = supportProblem(note.basedOn(), named);
            if (support != null) problems.add(support);
            if (note.basedOn().stream()
                    .anyMatch(id ->
                            named.containsKey(id) && named.get(id).result().summaryOnly()))
                problems.add("this note rests on a practice that requires summary location");
            String anchorError = anchorProblem(note, named, anchors);
            if (anchorError != null) problems.add(anchorError);
            problems.forEach(problem -> errors.add(label + ": " + problem));
            if (problems.isEmpty()) {
                anchors.add(note.problemId() + "#" + note.citationIndex());
                var basedOn = new ArrayList<>(note.basedOn());
                basedOn.add(note.problemId());
                inline.add(new InlineNote(
                        note.body(),
                        Evidence.refs(basedOn, named),
                        new Anchor(note.problemId(), note.citationIndex())));
            }
        }

        var spoken = new HashSet<String>();
        if (summaryBasedOn != null)
            summaryBasedOn.forEach(id -> {
                if (named.containsKey(id)) spoken.add(named.get(id).ref().resultId());
            });
        noteInputs.forEach(note -> note.basedOn().forEach(id -> {
            if (named.containsKey(id)) spoken.add(named.get(id).ref().resultId());
        }));
        errors.addAll(coverageProblems(decided, decidedNamed, spoken, results));
        var withheld = new ArrayList<Withheld>();
        decided.forEach((id, disposition) -> {
            if (disposition.equals("RAISE")) return;
            var witnessIds = decisionInputs.stream()
                    .filter(decision -> decision.resultId().equals(id))
                    .findFirst()
                    .map(DecisionInput::witnessIds)
                    .orElse(List.of());
            withheld.add(new Withheld(List.of(new Evidence.Ref(id, null)), disposition, witnessIds));
        });
        return errors.isEmpty()
                ? new Read(new ComposedReview(summary, inline, withheld), List.of())
                : new Read(null, errors);
    }

    private static @Nullable Integer citationIndex(JsonNode value) {
        if (value.isNumber() && Js.isSafeInteger(value) && value.doubleValue() >= 0) return (int) value.asLong();
        if (value.isString() && Js.trim(value.asString()).matches("\\d+")) {
            try {
                return Integer.parseInt(Js.trim(value.asString()));
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }
        return null;
    }

    /** A list of non-empty id strings, trimmed; null when the value is not one. */
    static @Nullable List<String> ids(JsonNode value) {
        if (!value.isArray()) return null;
        var ids = new ArrayList<String>();
        for (JsonNode id : value) {
            if (!id.isString() || Js.blank(id.asString())) return null;
            ids.add(Js.trim(id.asString()));
        }
        return ids;
    }

    private static @Nullable String textProblem(String value, int target, String example) {
        if (Js.blank(value)) return "body is required: the complete text, as the developer will read it";
        if (value.length() > target * 2)
            return "body must be at most " + target * 2 + " characters; this one is " + value.length() + ". Aim for "
                    + target
                    + " characters. "
                    + "Keep the supported concern, evidence, qualifications and next action; remove repetition. "
                    + "Corrected text example (synthetic; use this work's facts): " + Js.quote(example)
                    + ". Send the whole review again";
        if (value.contains(RESERVED_MARKER))
            return "body may not contain an HTML comment (`<!--`); the server writes its own markers there";
        if (ENVELOPE_TAIL.matcher(value).find())
            return "body ends in quote and bracket characters left over from a JSON envelope; end it on its last sentence";
        return null;
    }

    private static @Nullable String supportProblem(List<String> basedOn, Map<String, Evidence.Named> named) {
        if (basedOn.isEmpty())
            return "basedOn is required: the id of every admitted result or problem this text speaks about";
        var unknown = basedOn.stream().filter(id -> !named.containsKey(id)).toList();
        if (!unknown.isEmpty())
            return "basedOn names " + String.join(", ", unknown)
                    + ", which is not one of the NOT_MET results or problems this review may rest on (basedOn takes their `resultId` or `problemId`)";
        var notConcerns = basedOn.stream()
                .filter(id -> !Objects.requireNonNull(named.get(id)).result().notMet())
                .toList();
        return notConcerns.isEmpty()
                ? null
                : "basedOn names " + String.join(", ", notConcerns)
                        + ", which is not a NOT_MET result or one of its problems. The review on the work speaks only about NOT_MET results and their problems; it never acknowledges a MET result";
    }

    private static @Nullable String anchorProblem(
            NoteInput note, Map<String, Evidence.Named> named, Set<String> anchors) {
        var entry = named.get(note.problemId());
        if (entry == null)
            return "anchor names " + note.problemId() + ", which is not a problem this review may rest on";
        var problem = entry.result().problems().stream()
                .filter(candidate -> candidate.problemId().equals(note.problemId()))
                .findFirst()
                .orElse(null);
        if (problem == null)
            return "anchor names " + note.problemId()
                    + ", which is a result: a note sits on a citation of one of its problems, named by its problemId";
        if (!note.basedOn().contains(note.problemId())
                && !note.basedOn().contains(entry.ref().resultId()))
            return "anchor names " + note.problemId()
                    + ", which this note's basedOn does not, itself or through its result";
        if (note.citationIndex() >= problem.citations().size())
            return note.problemId() + " has no citation " + note.citationIndex();
        if (!isTrue(problem.citations().get(note.citationIndex()).path("anchorable")))
            return "citation " + note.citationIndex() + " of " + note.problemId()
                    + " is not a line of this change, so no note can sit on it";
        return anchors.contains(note.problemId() + "#" + note.citationIndex())
                ? "another note already sits on citation " + note.citationIndex() + " of " + note.problemId()
                : null;
    }

    private static List<String> coverageProblems(
            Map<String, String> decided, Set<String> named, Set<String> spoken, Map<String, Evidence.Result> results) {
        var errors = new ArrayList<String>();
        var missing = results.entrySet().stream()
                .filter(entry -> entry.getValue().notMet() && !named.contains(entry.getKey()))
                .map(Map.Entry::getKey)
                .toList();
        if (!missing.isEmpty())
            errors.add(String.join(", ", missing)
                    + " has no decision; decide each NOT_MET result: RAISE it, or withhold it with its reason");
        decided.forEach((id, disposition) -> {
            if (disposition.equals("RAISE") && !spoken.contains(id))
                errors.add(
                        id
                                + " is decided RAISE, and no text speaks about it or its problems; speak about it, or decide again");
            else if (!disposition.equals("RAISE") && spoken.contains(id))
                errors.add(id + " is withheld as " + disposition
                        + ", and a text speaks about it or its problems; a result is raised or withheld, never both");
        });
        return errors;
    }

    /** ALREADY_SAID needs a recorded communication; NO_MATERIAL_CHANGE also needs advice available at capture. */
    private static List<String> witnessProblems(
            String reason, List<String> witnessIds, Map<String, Witness> witnesses) {
        var problems = new ArrayList<String>();
        var unknown =
                witnessIds.stream().filter(id -> !witnesses.containsKey(id)).toList();
        if (!unknown.isEmpty())
            problems.add("witnessIds names " + String.join(", ", unknown)
                    + ", which is not a statement shown under what was already said on this work");
        boolean alreadySaid = reason.equals("ALREADY_SAID");
        String flag = alreadySaid ? "eligibleForAlreadySaid" : "eligibleForPriorAdvice";
        var ineligible = witnessIds.stream()
                .filter(id -> witnesses.containsKey(id)
                        && !(alreadySaid
                                ? witnesses.get(id).eligibleForAlreadySaid()
                                : witnesses.get(id).eligibleForPriorAdvice()))
                .toList();
        if (!ineligible.isEmpty())
            problems.add("witnessIds names " + String.join(", ", ineligible)
                    + ", which is shown as context but cannot stand as advice for " + reason + " (" + flag
                    + " is false)");
        if (PRIOR_ADVICE_REASONS.contains(reason) && witnessIds.isEmpty())
            problems.add(reason
                    + " names in witnessIds where this work already received the advice: the witnessId of a statement marked "
                    + flag + "; without one, raise it or choose another reason");
        return problems;
    }

    /** The finishing turn when a review session ends without its final call. */
    static String finishText(List<String> undecided) {
        String owed = undecided.isEmpty()
                ? "## Unfinished\nThe review on this work is not final yet."
                : "## Undecided\nThe review leaves these NOT_MET results undecided: " + String.join(", ", undecided)
                        + ".";
        return owed
                + "\nCheck the final review with check_package, then publish_package using the returned draftId. A review that says nothing is still a checked draft followed by publish_package. No prose outside the calls.";
    }
}
