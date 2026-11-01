package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.Js.isRecord;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.listOf;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.string;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Feedback for the developer's practice pages and the mentor conversation: what it is composed from, the
 * {@code check_package} tool it is stored with, and the checks that store or skip each piece.
 */
final class PrivateFeedback {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final JsonMapper JSON_READER = new JsonMapper();

    static final List<String> DESTINATIONS = List.of("PRACTICE_PAGE", "CONVERSATION");
    static final List<String> ACTIONS = List.of("NEW", "SUPERSEDE", "WITHHOLD");
    private static final int COMPOSITION_BLOCK_LIMIT = 32_000;
    private static final Pattern UUID_TEXT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private record Guidance(int target, String example) {}

    private static final Map<String, Guidance> TEXT_GUIDANCE = orderedGuidance();

    private static Map<String, Guidance> orderedGuidance() {
        var guidance = new LinkedHashMap<String, Guidance>();
        guidance.put(
                "body",
                new Guidance(
                        600,
                        "Both changes name the new behavior but leave its purpose unstated. Reviewers cannot judge the scope from that alone."));
        guidance.put(
                "nextStep",
                new Guidance(
                        300,
                        "Before your next review request, check that the description explains why the change is needed."));
        guidance.put(
                "situation",
                new Guidance(
                        300,
                        "The developer names changes without explaining their purpose across two review requests."));
        guidance.put(
                "capability",
                new Guidance(
                        300,
                        "The developer can distinguish a description of changed behavior from a reason for the change."));
        guidance.put(
                "evidenceSummary",
                new Guidance(
                        600,
                        "The descriptions of ‘Update sign-in’ and ‘Add search’ name the changes but give no reason for them."));
        guidance.put(
                "inConversationLearning",
                new Guidance(
                        300,
                        "The developer explains which decision a reviewer cannot assess without knowing the change’s purpose."));
        guidance.put(
                "alreadySaid",
                new Guidance(300, "Earlier practice-page feedback asked for the reason behind each change."));
        return guidance;
    }

    private static int bound(String field) {
        return switch (field) {
            case "title" -> 255;
            case "supersedesThreadKey" -> 64;
            default -> Objects.requireNonNull(TEXT_GUIDANCE.get(field)).target() * 2;
        };
    }

    private static final List<String> NOTE_FIELDS =
            List.of("situation", "capability", "evidenceSummary", "inConversationLearning", "alreadySaid");

    static final String NUDGE =
            "The admitted results with their problems and the history are in this turn, and nothing else decides a "
                    + "piece. Persist the feedback you have "
                    + "with check_package now, then publish_package with its draftId — and a WITHHOLD with its reason for each NOT_MET practice you decided "
                    + "to stay quiet about. Use tools only from this point onward; no planning prose.";

    private PrivateFeedback() {}

    /** The destinations, limits and copy forms one composition may write, as the request stages them. */
    static final class Request {
        final Map<String, Integer> destinations;
        final int minDistinctItems;
        final List<String> onWorkCopyForms;

        Request(Map<String, Integer> destinations, int minDistinctItems, List<String> onWorkCopyForms) {
            this.destinations = new LinkedHashMap<>(destinations);
            this.minDistinctItems = minDistinctItems;
            this.onWorkCopyForms = List.copyOf(onWorkCopyForms);
        }

        static Optional<Request> of(JsonNode request) {
            JsonNode destinations = request.path("destinations");
            if (!destinations.isObject()) return Optional.empty();
            var bounds = new LinkedHashMap<String, Integer>();
            for (String destination : List.of("ON_WORK", "PRACTICE_PAGE", "CONVERSATION")) {
                JsonNode maxPieces = destinations.path(destination).path("maxPieces");
                if (maxPieces.isIntegralNumber() && maxPieces.asInt() >= 1) bounds.put(destination, maxPieces.asInt());
            }
            var forms = listOf(request.path("onWorkCopyForms")).stream()
                    .map(JsonNode::asString)
                    .toList();
            return bounds.isEmpty()
                    ? Optional.empty()
                    : Optional.of(
                            new Request(bounds, request.path("minDistinctItems").asInt(2), forms));
        }

        boolean open(String destination) {
            return destinations.containsKey(destination);
        }

        boolean anyPrivate() {
            return DESTINATIONS.stream().anyMatch(this::open);
        }

        List<String> openPrivate() {
            return DESTINATIONS.stream().filter(this::open).toList();
        }
    }

    /** One piece as stored: what the composer named, with its support resolved to admitted references. */
    record Piece(
            String destination,
            String practiceSlug,
            String action,
            List<Evidence.Ref> basedOn,
            @Nullable String supersedesThreadKey,
            @Nullable String withholdReason,
            @Nullable List<String> witnessIds,
            @Nullable String title,
            @Nullable String body,
            @Nullable String nextStep,
            @Nullable Map<String, String> notes) {
        boolean delivers() {
            return !action.equals("WITHHOLD");
        }

        /** The words of a conversation piece: title, then each note with its label. */
        String feedbackBody() {
            var sections = new ArrayList<String>();
            for (String text : new String[] {title, body, nextStep})
                if (text != null && !text.isEmpty()) sections.add(text);
            var labels = Map.of(
                    "situation", "Situation",
                    "capability", "Capability",
                    "evidenceSummary", "Evidence",
                    "inConversationLearning", "In conversation",
                    "alreadySaid", "Earlier feedback");
            if (notes != null)
                for (String field : NOTE_FIELDS) {
                    String text = notes.get(field);
                    if (text != null && !Js.blank(text)) sections.add(labels.get(field) + ": " + text);
                }
            return String.join("\n\n", sections);
        }
    }

    /** A queued conversation piece a new piece may replace. */
    record PreparedTarget(String threadKey, String feedbackId, String destination, String practiceSlug) {}

    // ---------------------------------------------------------------- routing

    /**
     * Closes each private destination the server did not route, and returns, per NOT_MET practice, the open
     * destinations that may carry it. What earlier attempts prepared is this attempt's starting point: a practice
     * decided on a destination is not carried there again, and that destination keeps only the room the earlier pieces
     * left. A destination that decides every practice already is closed.
     *
     * @return the routes, and whether a routing read failed (as opposed to being refused)
     */
    static Map<String, List<String>> closeUnrouted(Request request, Admission admission, List<String> failures) {
        var notMet = Evidence.notMetPractices(admission.results());
        for (String destination : DESTINATIONS) {
            Integer bounds = request.destinations.get(destination);
            if (bounds == null) continue;
            var decided = admission.decided().getOrDefault(destination, Set.of());
            var undecided =
                    notMet.stream().filter(slug -> !decided.contains(slug)).toList();
            if (undecided.isEmpty()) {
                if (!decided.isEmpty()) request.destinations.remove(destination);
                continue;
            }
            request.destinations.put(
                    destination, Math.max(0, bounds - admission.deliveries().getOrDefault(destination, 0)));
            JsonNode read = destination.equals("PRACTICE_PAGE")
                    ? admission.practicePageSupport()
                    : admission.conversationEligibility();
            if (!admission.complete(destination)) {
                request.destinations.remove(destination);
                if (read == null || !"REFUSED".equals(string(read.path("state")))) failures.add(destination);
            } else if (undecided.stream().noneMatch(slug -> admission.routedTo(destination, slug))) {
                request.destinations.remove(destination);
            }
        }
        var routes = new LinkedHashMap<String, List<String>>();
        for (String slug : notMet)
            routes.put(
                    slug,
                    DESTINATIONS.stream()
                            .filter(destination -> request.open(destination)
                                    && !admission
                                            .decided()
                                            .getOrDefault(destination, Set.of())
                                            .contains(slug)
                                    && admission.routedTo(destination, slug))
                            .toList());
        return routes;
    }

    /** The NOT_MET practices an open destination may carry. */
    static List<String> composable(Admission admission, Map<String, List<String>> routes) {
        return Evidence.notMetPractices(admission.results()).stream()
                .filter(slug -> !routes.getOrDefault(slug, List.of()).isEmpty())
                .toList();
    }

    /**
     * Per composable practice, the open destinations no stored piece decides it on: a piece decides, on its own
     * destination, the practice it names and every practice in its support. A resumed attempt reads its progress the
     * same way.
     */
    static Map<String, List<String>> undecided(
            Admission admission, List<Piece> feedback, Map<String, List<String>> routes) {
        var practiceOf = new LinkedHashMap<String, String>();
        admission
                .results()
                .forEach(result -> practiceOf.put(
                        Evidence.text(result.path("resultId")), Evidence.text(result.path("practiceSlug"))));
        var decided = new HashMap<String, Set<String>>();
        for (var piece : feedback) {
            var practices = decided.computeIfAbsent(piece.destination(), ignored -> new HashSet<>());
            practices.add(piece.practiceSlug());
            piece.basedOn().forEach(ref -> {
                String practice = practiceOf.get(ref.resultId());
                if (practice != null) practices.add(practice);
            });
        }
        var open = new LinkedHashMap<String, List<String>>();
        for (String slug : composable(admission, routes)) {
            var destinations = routes.getOrDefault(slug, List.of()).stream()
                    .filter(destination ->
                            !decided.getOrDefault(destination, Set.of()).contains(slug))
                    .toList();
            if (!destinations.isEmpty()) open.put(slug, destinations);
        }
        return open;
    }

    // ---------------------------------------------------------------- history staged for the session

    /** Writes the private history read as the session's files, or removes them when there was none. */
    static void stageHistory(@Nullable JsonNode history, ComposerFiles files) {
        files.remove(ComposerFiles.DELIVERED);
        files.remove(ComposerFiles.PREPARED);
        if (history == null) return;
        var delivered = JSON.objectNode();
        delivered.set("readAt", history.path("readAt"));
        delivered.set("coverage", history.path("coverage"));
        delivered.put("recordRole", "RECORDED_DELIVERY");
        delivered.set("feedback", history.path("delivered"));
        files.put(ComposerFiles.DELIVERED, JsonText.stringify(delivered, 2).getBytes(StandardCharsets.UTF_8));
        var prepared = JSON.objectNode();
        prepared.set("readAt", history.path("readAt"));
        prepared.set("coverage", history.path("coverage"));
        prepared.put("recordRole", "PREPARED_NOT_DELIVERED");
        prepared.set("prepared", history.path("queued"));
        files.put(ComposerFiles.PREPARED, JsonText.stringify(prepared, 2).getBytes(StandardCharsets.UTF_8));
    }

    /** Supersession is limited to unread thread keys present in this snapshot. */
    static List<PreparedTarget> preparedTargets(ComposerFiles files, ObjectMapper mapper) {
        var prepared = files.json(ComposerFiles.PREPARED, mapper).orElse(JSON.missingNode());
        var targets = new ArrayList<PreparedTarget>();
        for (JsonNode entry : listOf(isRecord(prepared) ? prepared.path("prepared") : JSON.missingNode())) {
            String threadKey = string(entry.path("threadKey"));
            String feedbackId = string(entry.path("feedbackId"));
            String destination = string(entry.path("destination"));
            String practiceSlug = string(entry.path("practiceSlug"));
            if (threadKey != null
                    && !Js.blank(threadKey)
                    && feedbackId != null
                    && UUID_TEXT.matcher(feedbackId).matches()
                    && destination != null
                    && List.of("ON_WORK", "PRACTICE_PAGE", "CONVERSATION").contains(destination)
                    && practiceSlug != null
                    && !Js.blank(practiceSlug))
                targets.add(new PreparedTarget(threadKey, feedbackId, destination, practiceSlug));
        }
        return targets;
    }

    /**
     * Each delivered piece, by {@code destination:practice:feedbackId}, with what it may stand for: words said by the
     * history read are communication whether their claim is current or stale; only a current claim delivered before
     * this work was captured is advice it could have answered.
     */
    static Map<String, ReviewOnWork.Witness> deliveredWitnesses(
            ComposerFiles files, ObjectMapper mapper, @Nullable String capturedAt) {
        var witnesses = new LinkedHashMap<String, ReviewOnWork.Witness>();
        var history = files.json(ComposerFiles.DELIVERED, mapper).orElse(JSON.missingNode());
        if (!isRecord(history)
                || !"RECORDED_DELIVERY".equals(string(history.path("recordRole")))
                || !history.path("feedback").isArray()) return witnesses;
        String readAt = string(history.path("readAt"));
        for (JsonNode entry : history.path("feedback")) {
            if (!isRecord(entry)) continue;
            String destination = string(entry.path("destination"));
            String currentness = string(entry.path("recordedClaimCurrentness"));
            String id = entry.path("id").isString() ? Js.trim(entry.path("id").asString()) : "";
            boolean recorded = ("PRACTICE_PAGE".equals(destination) || "CONVERSATION".equals(destination))
                    && !id.isEmpty()
                    && ("CURRENT".equals(currentness) || "STALE".equals(currentness))
                    && entry.path("withdrawn").isBoolean()
                    && !entry.path("withdrawn").asBoolean()
                    && entry.path("body").isString()
                    && !Js.blank(entry.path("body").asString());
            if (!recorded) continue;
            var witness = new ReviewOnWork.Witness(
                    "CURRENT".equals(currentness) && ReviewOnWork.deliveredBy(entry.path("deliveredAt"), capturedAt),
                    ReviewOnWork.deliveredBy(entry.path("deliveredAt"), readAt));
            for (JsonNode support : listOf(entry.path("basedOn")))
                if (isRecord(support) && "NOT_MET".equals(string(support.path("outcome"))))
                    witnesses.put(
                            destination + ":"
                                    + (support.path("practiceSlug").isString()
                                            ? Js.trim(
                                                    support.path("practiceSlug").asString())
                                            : "")
                                    + ":" + id,
                            witness);
        }
        return witnesses;
    }

    // ---------------------------------------------------------------- the turn

    private static JsonNode coordinatesOf(JsonNode citation) {
        if (!isRecord(citation)) return citation;
        var coordinates = ((ObjectNode) citation).deepCopy();
        coordinates.remove("quote");
        coordinates.remove("verification");
        return coordinates;
    }

    /** One admitted result as the private composer sees it: its citations by coordinates, without quoted lines. */
    static ObjectNode composerView(ObjectNode result) {
        var citations = JSON.arrayNode();
        listOf(result.path("citations")).forEach(citation -> citations.add(coordinatesOf(citation)));
        String outcome = string(result.path("outcome"));
        var view = JSON.objectNode();
        if (!"NOT_MET".equals(outcome) && !"MET".equals(outcome)) {
            ReviewOnWork.putDefined(view, "resultId", result.path("resultId"));
            ReviewOnWork.putDefined(view, "practiceSlug", result.path("practiceSlug"));
            ReviewOnWork.putDefined(view, "outcome", result.path("outcome"));
            view.set("evidence", ReviewOnWork.qualificationEvidence(result));
            view.set("citations", citations);
            return view;
        }
        result.properties().forEach(property -> {
            if (!Set.of("citations", "example", "problems").contains(property.getKey()))
                view.set(property.getKey(), property.getValue());
        });
        JsonNode evidence = result.path("evidence");
        if (isRecord(evidence)) {
            var shown = ((ObjectNode) evidence).deepCopy();
            shown.set("citations", citations);
            view.set("evidence", shown);
        } else ReviewOnWork.putDefined(view, "evidence", evidence);
        JsonNode example = result.path("example");
        if (isRecord(example)) {
            var shown = ((ObjectNode) example).deepCopy();
            shown.set("citation", coordinatesOf(example.path("citation")));
            if (example.path("citation").isMissingNode()) shown.remove("citation");
            view.set("example", shown);
        }
        view.set("citations", citations);
        var problems = view.putArray("problems");
        for (JsonNode problem : listOf(result.path("problems"))) {
            var shown = ((ObjectNode) problem).deepCopy();
            var problemCitations = JSON.arrayNode();
            listOf(problem.path("citations")).forEach(citation -> problemCitations.add(coordinatesOf(citation)));
            shown.set("citations", problemCitations);
            problems.add(shown);
        }
        return view;
    }

    private static String stagedBlock(ComposerFiles files, String label, String path) {
        var text = files.text(path).orElse(null);
        if (text == null) return "";
        String trimmed = Js.trim(text);
        return trimmed.length() > COMPOSITION_BLOCK_LIMIT
                ? "### " + label + " — too large to show here; not available in this turn\n"
                : "### " + label + "\n```json\n" + trimmed + "\n```\n";
    }

    /** Composition receives criteria, but not measuring leads that could start another assessment. */
    private static String notMetCriteria(ComposerFiles files, List<ObjectNode> results) {
        return Evidence.notMetPractices(results).stream()
                .map(slug -> {
                    var text = files.text(ComposerFiles.criteria(slug)).orElse(null);
                    if (text == null)
                        return "### Criteria of `" + slug
                                + "` — not staged for this review; nothing is known about them here\n";
                    String trimmed = Js.trim(text);
                    if (trimmed.isEmpty())
                        return "### Criteria of `" + slug + "` — staged empty; no standard is available here\n";
                    if (trimmed.length() > COMPOSITION_BLOCK_LIMIT)
                        return "### Criteria of `" + slug + "` — too large to show here; not available in this turn\n";
                    String fence = "`".repeat(Math.max(3, CapturedWork.longestBacktickRun(trimmed) + 1));
                    return "### Criteria of `" + slug + "`\n" + fence + "markdown\n" + trimmed + "\n" + fence + "\n";
                })
                .collect(Collectors.joining("\n"));
    }

    /** History can be missing without being empty; missing and empty carry different evidence. */
    private static String unstagedHistory(String label) {
        return "### " + label + " — not staged for this review; what it would record is unknown here, not empty\n";
    }

    /**
     * The history the producer staged, with the bodies it made eligible. When the bodies do not fit, the facts are shown
     * without them.
     */
    private static String priorFeedbackFacts(
            ComposerFiles files, ObjectMapper mapper, String label, String path, String key) {
        if (!files.exists(path)) return unstagedHistory(label);
        String unreadable = "### " + label + " — staged but not readable; what it records is unknown here\n";
        var history = files.json(path, mapper).orElse(null);
        if (history == null || !isRecord(history) || !history.path(key).isArray()) return unreadable;
        for (JsonNode entry : history.path(key))
            if (!isRecord(entry)
                    || (!entry.path("body").isMissingNode()
                            && !entry.path("body").isNull()
                            && !entry.path("body").isString())) return unreadable;
        if (history.path(key).isEmpty())
            return "### " + label + " — staged with no entries\n```json\n" + JsonText.stringify(history, 1) + "\n```\n";
        String whole = JsonText.stringify(history, 1);
        if (whole.length() <= COMPOSITION_BLOCK_LIMIT) return "### " + label + "\n```json\n" + whole + "\n```\n";
        var projected = JSON.arrayNode();
        for (JsonNode entry : history.path(key)) {
            var facts = JSON.objectNode();
            entry.properties().forEach(property -> {
                if (!property.getKey().equals("body")) facts.set(property.getKey(), property.getValue());
            });
            projected.add(facts);
        }
        var copy = ((ObjectNode) history).deepCopy();
        copy.set(key, projected);
        String text = JsonText.stringify(copy, 1);
        return text.length() > COMPOSITION_BLOCK_LIMIT
                ? "### " + label + " — too large to show here; not available in this turn\n"
                : "### " + label + " — its bodies are too large to show here; the facts follow\n```json\n" + text
                        + "\n```\n";
    }

    /** A planned public decision proves neither delivery nor prior private advice. */
    private static String plannedReviewFacts(ReviewOnWork.@Nullable ComposedReview review) {
        if (review == null) return "";
        var facts = JSON.objectNode();
        if (review.summary() == null) facts.putNull("summary");
        else {
            var summary = facts.putObject("summary");
            summary.set("basedOn", refs(review.summary().basedOn()));
            summary.put("body", review.summary().body());
        }
        var inline = facts.putArray("inline");
        for (var note : review.inline()) {
            var shown = inline.addObject();
            shown.set("basedOn", refs(note.basedOn()));
            var anchor = shown.putObject("anchor");
            anchor.put("problemId", note.anchor().problemId());
            anchor.put("citationIndex", note.anchor().citationIndex());
            shown.put("body", note.body());
        }
        var withheld = facts.putArray("withheld");
        for (var decision : review.withheld()) {
            var shown = withheld.addObject();
            shown.set("basedOn", refs(decision.basedOn()));
            shown.put("reason", decision.reason());
            var witnesses = shown.putArray("witnessIds");
            decision.witnessIds().forEach(witnesses::add);
        }
        return "### The review composed for this work\nThis decision is not a receipt of provider delivery. It may have entered public handling, whose final gates can still withhold it. Only delivered-history records establish prior communication. Private destinations remain independently useful and may make the same supported point."
                + "\n```json\n" + JsonText.stringify(facts, 1) + "\n```\n";
    }

    static ArrayNode refs(List<Evidence.Ref> refs) {
        var list = JSON.arrayNode();
        refs.forEach(ref -> list.add(ref.json()));
        return list;
    }

    /** The turn that asks for the private feedback, after the captured-work brief. */
    static String turn(
            ComposerFiles files,
            ObjectMapper mapper,
            Request request,
            Admission admission,
            ReviewOnWork.@Nullable ComposedReview review,
            Map<String, List<String>> routes,
            List<String> notReached) {
        var results = admission.results();
        String openDestinations = request.openPrivate().stream()
                .map(destination -> destination + " (at most " + request.destinations.get(destination) + ")")
                .collect(Collectors.joining(", "));
        var closed = DESTINATIONS.stream()
                .filter(destination -> !request.open(destination))
                .toList();
        String closedNote = closed.isEmpty()
                ? ""
                : " Closed this turn, so write nothing for them: " + String.join(", ", closed) + ".";
        String coverageNote = Evidence.notReachedNote(notReached);
        String decidedNote = decidedNote(admission);
        String routing = request.openPrivate().stream()
                .map(destination -> destination + ": "
                        + composable(admission, routes).stream()
                                .filter(slug ->
                                        routes.getOrDefault(slug, List.of()).contains(destination))
                                .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("; "));
        var wrapper = JSON.objectNode();
        var list = wrapper.putArray("results");
        results.forEach(result -> list.add(composerView(result)));
        String admitted = JsonText.stringify(wrapper, 1);
        String problemHistory = "What earlier reviews recorded about this person";
        var context = new ArrayList<String>();
        context.add(stagedBlock(
                files,
                "The composition request (destinations, limits, copy forms)",
                SandboxLayout.FEEDBACK_COMPOSITION_PATH));
        context.add(
                files.exists(ComposerFiles.problems())
                        ? stagedBlock(files, problemHistory, ComposerFiles.problems())
                        : unstagedHistory(problemHistory));
        context.add(
                request.open("PRACTICE_PAGE")
                        ? stagedBlock(
                                files,
                                "PRACTICE_PAGE support: occurrences a new card may cite at this read",
                                ComposerFiles.SUPPORT)
                        : "");
        context.add(
                priorFeedbackFacts(files, mapper, "Recorded delivered feedback", ComposerFiles.DELIVERED, "feedback"));
        context.add(priorFeedbackFacts(
                files,
                mapper,
                "Prepared feedback (not delivered; supersession targets)",
                ComposerFiles.PREPARED,
                "prepared"));
        String contextText = context.stream().filter(block -> !block.isEmpty()).collect(Collectors.joining("\n"));
        return "## This turn\nThe review just finished. The criteria its NOT_MET practices were assessed against come first, as staged; its "
                + results.size()
                + " admitted result(s) follow them, one per practice. The MET and NOT_MET ones carry their rationale and their citations by coordinates; each NOT_MET one also carries its problems, the located problems it found, each with its own citations. The abstentions carry the limit they recorded, an open question or why the practice did not apply, and their citations by coordinates. The history follows them.\n\n"
                + notMetCriteria(files, results) + "\n"
                + "```json\n" + admitted + "\n```\n\n"
                + contextText + "\n"
                + plannedReviewFacts(review) + "\n"
                + "Destinations open this turn: " + openDestinations + "." + closedNote + decidedNote + "\n"
                + "The practices each open destination may carry: " + routing
                + ". Write no piece, not even a WITHHOLD, for a practice on a destination that does not list it.\n"
                + "A pattern claim needs at least " + request.minDistinctItems + " distinct pieces of work.\n\n"
                + coverageNote
                + "Check the complete package with check_package, then publish_package using its draftId — every piece you have in one call. Writing nothing on a destination is a correct and common outcome; say in one line why, and stop.";
    }

    /** What earlier attempts already decided: one list when both destinations agree, else per destination. */
    private static String decidedNote(Admission admission) {
        var page = admission.decided().getOrDefault("PRACTICE_PAGE", Set.of());
        var conversation = admission.decided().getOrDefault("CONVERSATION", Set.of());
        if (page.isEmpty() && conversation.isEmpty()) return "";
        String decided = page.equals(conversation)
                ? String.join(", ", new TreeSet<>(page))
                : DESTINATIONS.stream()
                        .filter(destination -> !admission
                                .decided()
                                .getOrDefault(destination, Set.of())
                                .isEmpty())
                        .map(destination -> String.join(
                                        ", ", new TreeSet<>(admission.decided().get(destination))) + " on "
                                + destination)
                        .collect(Collectors.joining(" and "));
        return " Private feedback that an earlier attempt of this review prepared already decides " + decided
                + "; it stands, and the limits above are the room it left.";
    }

    /** The finishing turn for practices the session left undecided on a destination. */
    static String finishText(Map<String, List<String>> undecided, Request request, List<Piece> feedback) {
        String notMet = undecided.entrySet().stream()
                .map(open -> open.getKey() + " on " + String.join(" and ", open.getValue()))
                .collect(Collectors.joining(", "));
        String room = request.openPrivate().stream()
                .map(destination -> {
                    int maxPieces = request.destinations.getOrDefault(destination, 0);
                    long used = feedback.stream()
                            .filter(piece -> piece.destination().equals(destination) && piece.delivers())
                            .count();
                    return destination + " " + Math.max(0, maxPieces - used) + " of " + maxPieces;
                })
                .collect(Collectors.joining(", "));
        return "## Undecided\nThe turn ended with no piece and no WITHHOLD for these practices, each with a "
                + "NOT_MET result: " + notMet + ". For each, persist the piece you decided on, or a "
                + "WITHHOLD with its reason (NO_MATERIAL_CHANGE, ALREADY_SAID, BELOW_BAR), in check_package before publish_package "
                + "call. Room left for new items: " + room
                + "; a destination with none left takes only a WITHHOLD. The "
                + "admitted results and their problems are in the turn above. Use tools only from this "
                + "point onward; no prose.";
    }

    // ---------------------------------------------------------------- the tool

    static final String TOOL_DESCRIPTION =
            "Check the complete package for this private destination without publishing it, one piece per "
                    + "practice. Include a decision for every eligible practice. A check creates no feedback or delivery. "
                    + "Correct any refusal and check again; then publish_package using the returned draftId. "
                    + "This is an intervention, not a measurement: it takes no outcome, severity or "
                    + "confidence, and no citation you typed yourself. "
                    + "Aim for at most 600 characters in a card body, 300 in its nextStep; keep title/body/nextStep separate. "
                    + "Mentor notes aim for 300 per field, 600 for evidenceSummary. "
                    + "More than twice a field's target is refused with a correction example, never truncated. "
                    + "Keep the evidence and its qualifications. No headings or internal labels in field contents. Use natural evidence references, never internal coordinates. "
                    + "Input example for a practice page: {\"feedback\":[{\"destination\":\"PRACTICE_PAGE\",\"practiceSlug\":\"<admitted practice slug>\",\"basedOn\":[\"<its NOT_MET resultId or problemId>\"],\"action\":\"NEW\",\"title\":\"Name the concern\",\"body\":\"Explain the supported concern.\",\"nextStep\":\"One action that addresses it.\"}]}. "
                    + "For WITHHOLD, send destination, practiceSlug, basedOn, action:\"WITHHOLD\", withholdReason and witnessIds:[]; omit title, body, nextStep and notes.";

    /** The JSON schema of check_package's arguments for the open destinations. */
    static ObjectNode toolParameters(List<String> practiceSlugs, Request request) {
        var enabled = request.openPrivate();
        var properties = new LinkedHashMap<String, ObjectNode>();
        properties.put(
                "destination",
                stringEnum(enabled, "Which surface this piece is for. Each has its own level and its own rules."));
        properties.put(
                "practiceSlug",
                stringEnum(practiceSlugs, "The primary practice. A practice-page card uses only this practice."));
        var basedOn = JSON.objectNode();
        basedOn.put("type", "array");
        basedOn.put("minItems", 1);
        basedOn.putObject("items").put("type", "string").put("minLength", 1);
        basedOn.put(
                "description",
                "What this rests on: admitted ids from this run, a result's resultId or, for one located problem, a problem's problemId. Related NOT_MET practices may support CONVERSATION only when each is eligible there.");
        properties.put("basedOn", basedOn);
        properties.put(
                "action",
                stringEnum(
                        ACTIONS,
                        "NEW to say something; SUPERSEDE to replace prepared feedback that has not been delivered; "
                                + "WITHHOLD to record, with a reason, that you decided to stay quiet."));
        var supersedes = JSON.objectNode();
        supersedes.put("type", "string");
        supersedes.put("maxLength", bound("supersedesThreadKey"));
        supersedes.put(
                "description",
                "Required for SUPERSEDE: the threadKey of an entry in the task-declared prepared-feedback file. "
                        + "You may not name a key that is not in that file.");
        properties.put("supersedesThreadKey", supersedes);
        var witnessIds = JSON.objectNode();
        witnessIds.put("type", "array");
        witnessIds.putObject("items").put("type", "string");
        witnessIds.put("description", "Delivered feedback IDs that witness prior advice; empty for BELOW_BAR.");
        properties.put("witnessIds", witnessIds);
        var withholdReason = JSON.objectNode();
        withholdReason.put("type", "string");
        var reasons = withholdReason.putArray("enum");
        ReviewOnWork.WITHHOLD_REASONS.forEach(reasons::add);
        properties.put("withholdReason", withholdReason);
        properties.put("title", text(bound("title"), "Names the issue in a few words. Never names the person."));
        properties.put(
                "body",
                text(
                        bound("body"),
                        "PRACTICE_PAGE only: aim for at most 600 characters. "
                                + "The evidenced pattern across the work and why it matters; the future way of working belongs to nextStep. Grounded in current results and their problems; never quote a line or claim change over time from the pre-run history. Markdown, read verbatim, without headings or internal labels. The UI renders the title and next step separately."));
        properties.put(
                "nextStep",
                text(
                        bound("nextStep"),
                        "PRACTICE_PAGE only: aim for at most 300 characters. "
                                + "The one repeatable way of working for the next piece of work; the body does not state it. No heading or label. Name the missing decision, not a heading/template unless the practice requires one; never provide paste-ready prose."));
        var notes = JSON.objectNode();
        notes.put("type", "object");
        notes.put("additionalProperties", false);
        notes.putArray("required")
                .add("situation")
                .add("capability")
                .add("evidenceSummary")
                .add("inConversationLearning");
        notes.put(
                "description",
                "CONVERSATION only. Notes TO the mentor, which composes the whole turn itself, later, "
                        + "with the live conversation in front of it. Write what it needs to know, never a "
                        + "sentence for it to say: anything phrased as a line of dialogue will be spoken, and "
                        + "will sound like a script. "
                        + "Aim for at most 300 characters per field, "
                        + "or 600 for evidenceSummary. No headings or internal labels in field contents; each field adds its own fact.");
        var noteProperties = notes.putObject("properties");
        noteProperties.set(
                "situation",
                text(
                        bound("situation"),
                        "The concern the problems have in common, as it can be observed, stated once rather than "
                                + "listing each item again. Your words about it, not words for it - third person, never "
                                + "addressed to the developer as 'you', and never a judgement of the person."));
        noteProperties.set(
                "capability",
                text(
                        bound("capability"),
                        "The self-check of the assessed behaviour this conversation should support: how the developer can tell whether their own work shows it, not only whether something is wired up. State the capability, not a solution such as a required heading/template, and not a question, script, diagnosis, or fixed tactic."));
        noteProperties.set(
                "evidenceSummary",
                text(
                        bound("evidenceSummary"),
                        "Where the concern occurred, compactly: each supporting work and problem located once. "
                                + "Summarise rather than inventing a quote; the original result evidence is "
                                + "staged separately for the mentor to inspect."));
        noteProperties.set(
                "inConversationLearning",
                text(
                        bound("inConversationLearning"),
                        "Understanding the developer articulates in a way that can be observed before the conversation ends: a distinction, decision, question, or self-check they say. Not a promise, future output, message text, or compliance target."));
        noteProperties.set(
                "alreadySaid",
                text(
                        bound("alreadySaid"),
                        "Optional. Relevant prior communication the shown history records, and movement the shown evidence supports. Omit when none is shown. Partial or missing history leaves coverage unknown: it never proves that nothing was said or that a concern was resolved."));
        properties.put("notes", notes);

        var branches = JSON.arrayNode();
        for (String destination : enabled) {
            branches.add(branch(properties, destination, "WITHHOLD"));
            branches.add(branch(properties, destination, "NEW"));
            if (destination.equals("CONVERSATION")) branches.add(branch(properties, destination, "SUPERSEDE"));
        }
        var parameters = JSON.objectNode();
        parameters.put("type", "object");
        parameters.put("additionalProperties", false);
        parameters.putArray("required").add("feedback");
        var feedback = parameters.putObject("properties").putObject("feedback");
        feedback.put("type", "array");
        feedback.put("minItems", 1);
        feedback.putObject("items").set("anyOf", branches);
        return parameters;
    }

    private static ObjectNode branch(Map<String, ObjectNode> properties, String destination, String action) {
        var fields = new ArrayList<>(List.of("destination", "practiceSlug", "basedOn", "action"));
        if (action.equals("WITHHOLD")) fields.addAll(List.of("withholdReason", "witnessIds"));
        else if (destination.equals("PRACTICE_PAGE")) fields.addAll(List.of("title", "body", "nextStep"));
        else {
            fields.addAll(List.of("title", "notes"));
            if (action.equals("SUPERSEDE")) fields.add("supersedesThreadKey");
        }
        var branch = JSON.objectNode();
        branch.put("type", "object");
        branch.put("additionalProperties", false);
        var required = branch.putArray("required");
        fields.forEach(required::add);
        var shown = branch.putObject("properties");
        properties.forEach((key, schema) -> {
            if (fields.contains(key)) shown.set(key, schema);
        });
        shown.set(
                "destination",
                JSON.objectNode()
                        .put("type", "string")
                        .set("enum", JSON.arrayNode().add(destination)));
        shown.set(
                "action",
                JSON.objectNode()
                        .put("type", "string")
                        .set("enum", JSON.arrayNode().add(action)));
        return branch;
    }

    private static ObjectNode stringEnum(List<String> values, String description) {
        var schema = JSON.objectNode();
        schema.put("type", "string");
        var choices = schema.putArray("enum");
        values.forEach(choices::add);
        schema.put("description", description);
        return schema;
    }

    private static ObjectNode text(int maxLength, String description) {
        var schema = JSON.objectNode();
        schema.put("type", "string");
        schema.put("maxLength", maxLength);
        schema.put("description", description);
        return schema;
    }

    // ---------------------------------------------------------------- storing pieces

    /** What one check_package call stores and skips, piece by piece. */
    static final class Draft {
        private final List<String> practiceSlugs;
        private final Request request;
        private final Admission admission;
        private final Map<String, Evidence.Result> results;
        private final List<PreparedTarget> preparedTargets;
        private final Map<String, ReviewOnWork.Witness> deliveredWitnesses;
        private final Map<String, List<String>> routes;
        private final Map<String, Integer> used = new LinkedHashMap<>();
        private final Set<String> seen = new HashSet<>();
        final List<Piece> feedback = new ArrayList<>();
        private boolean complete;

        boolean complete() {
            return complete;
        }

        Draft(
                List<String> practiceSlugs,
                Request request,
                Admission admission,
                List<PreparedTarget> preparedTargets,
                Map<String, ReviewOnWork.Witness> deliveredWitnesses,
                Map<String, List<String>> routes) {
            this.practiceSlugs = practiceSlugs;
            this.request = request;
            this.admission = admission;
            this.results = Evidence.byId(admission.results());
            this.preparedTargets = preparedTargets;
            this.deliveredWitnesses = deliveredWitnesses;
            this.routes = routes;
        }

        List<PreparedTarget> preparedTargets() {
            return preparedTargets;
        }

        /** The tool's answer to one call or one piece, and how many pieces it validated. */
        record Outcome(String text, int count) {
            boolean valid() {
                return count > 0;
            }
        }

        Outcome call(JsonNode raw) {
            complete = false;
            JsonNode input = ReviewOnWork.unwrapArguments(raw);
            JsonNode listed = isRecord(input) ? input.path("feedback") : JSON.missingNode();
            if (listed.isString()) {
                try {
                    listed = JSON_READER.readTree(listed.asString());
                } catch (RuntimeException notJson) {
                    return new Outcome("feedback refused — feedback must be an array of pieces", 0);
                }
            }
            if (isRecord(listed)) listed = JSON.arrayNode().add(listed);
            if (!listed.isArray()) return new Outcome("feedback refused — feedback must be an array of pieces", 0);
            if (listed.isEmpty())
                return new Outcome("feedback refused — the list is empty; send every piece you have ready in it", 0);
            var lines = new ArrayList<String>();
            int stored = 0;
            int index = 1;
            complete = true;
            for (JsonNode piece : listed) {
                var outcome = store(prepare(piece));
                stored += outcome.count();
                if (!outcome.valid()) complete = false;
                lines.add("#" + index++ + ": " + outcome.text());
            }
            return new Outcome(String.join("\n", lines), stored);
        }

        /** Null unused copy fields and absent witness lists carry no evidence; basedOn is never inferred. */
        private static JsonNode prepare(JsonNode piece) {
            if (!isRecord(piece)) return piece;
            var prepared = JSON.objectNode();
            var absent = Set.of("supersedesThreadKey", "title", "body", "nextStep", "notes", "withholdReason");
            piece.properties().forEach(property -> {
                if (!property.getValue().isNull() || !absent.contains(property.getKey()))
                    prepared.set(property.getKey(), property.getValue());
            });
            if (prepared.path("witnessIds").isNull()) prepared.set("witnessIds", JSON.arrayNode());
            if (prepared.path("notes").isObject()
                    && prepared.path("notes").path("alreadySaid").isNull()) {
                var notes = ((ObjectNode) prepared.path("notes")).deepCopy();
                notes.remove("alreadySaid");
                prepared.set("notes", notes);
            }
            return prepared;
        }

        private record Read(
                String destination,
                String practiceSlug,
                String action,
                List<String> basedOn,
                @Nullable String supersedesThreadKey,
                @Nullable List<String> witnessIds,
                @Nullable String withholdReason,
                @Nullable String title,
                @Nullable String body,
                @Nullable String nextStep,
                @Nullable Map<String, String> notes) {}

        private Outcome store(JsonNode value) {
            Object read = readPiece(value);
            if (read instanceof String refusal) return new Outcome(refusal, 0);
            var piece = (Read) read;
            Integer bounds = request.destinations.get(piece.destination());
            if (bounds == null)
                return new Outcome(
                        piece.destination() + " is not a destination this run may write for (open: "
                                + String.join(", ", request.openPrivate()) + "); skipped.",
                        0);
            String key = piece.destination() + ":" + piece.practiceSlug();
            if (seen.contains(key))
                return new Outcome(
                        "already have a " + piece.destination() + " piece for " + piece.practiceSlug()
                                + ", and the first one stands; nothing to resend for it. Skipped.",
                        0);
            boolean delivers = !piece.action().equals("WITHHOLD");
            int usedHere = used.getOrDefault(piece.destination(), 0);
            if (delivers && usedHere >= bounds)
                return new Outcome(
                        piece.destination() + " holds at most " + bounds
                                + " piece(s) and is full: this one does not fit — WITHHOLD it with BELOW_BAR or leave it out. Skipped.",
                        0);
            String rejection = validate(piece);
            if (rejection != null) return new Outcome(rejection, 0);
            var named = Evidence.named(results);
            var supporting = new LinkedHashSet<String>();
            piece.basedOn().forEach(id -> {
                var entry = named.get(id);
                if (entry != null) supporting.add(entry.result().practiceSlug());
            });
            String ineligible = supporting.stream()
                    .filter(slug -> !routes.getOrDefault(slug, List.of()).contains(piece.destination()))
                    .findFirst()
                    .orElse(null);
            if (ineligible != null)
                return new Outcome(
                        piece.destination() + " cannot carry feedback about " + piece.practiceSlug() + " this run ("
                                + admission.destinationRefusal(piece.destination(), ineligible)
                                + "); write no piece for it there, not even a WITHHOLD. Skipped.",
                        0);
            if (piece.destination().equals("PRACTICE_PAGE") && delivers) {
                int pieces = admission.negativePiecesOfWork(piece.practiceSlug());
                if (pieces < request.minDistinctItems)
                    return new Outcome(
                            "PRACTICE_PAGE needs a pattern across at least " + request.minDistinctItems
                                    + " pieces of work, and "
                                    + piece.practiceSlug() + " has support on " + pieces
                                    + " (the PRACTICE_PAGE support read); WITHHOLD it "
                                    + "with BELOW_BAR. Skipped.",
                            0);
            }
            var stored = new Piece(
                    piece.destination(),
                    piece.practiceSlug(),
                    piece.action(),
                    Evidence.refs(piece.basedOn(), named),
                    piece.supersedesThreadKey(),
                    piece.withholdReason(),
                    piece.witnessIds(),
                    piece.title(),
                    piece.body(),
                    piece.nextStep(),
                    piece.notes());
            seen.add(key);
            if (delivers) used.put(piece.destination(), usedHere + 1);
            feedback.add(stored);
            return new Outcome(
                    "validated a " + piece.destination() + " piece for " + piece.practiceSlug() + " (" + piece.action()
                            + "); " + used.getOrDefault(piece.destination(), 0) + "/" + bounds
                            + " used on that destination.",
                    1);
        }

        /** The piece as the schema reads it, or every reason it is not one. */
        private Object readPiece(JsonNode value) {
            if (!isRecord(value)) return "piece: expected an object";
            var issues = new ArrayList<String>();
            var known = Set.of(
                    "destination",
                    "practiceSlug",
                    "action",
                    "basedOn",
                    "supersedesThreadKey",
                    "witnessIds",
                    "withholdReason",
                    "title",
                    "body",
                    "nextStep",
                    "notes");
            for (String key : value.propertyNames())
                if (!known.contains(key)) issues.add("piece: unrecognized key \"" + key + "\"");
            String destination = string(value.path("destination"));
            if (destination == null || !DESTINATIONS.contains(destination))
                issues.add("destination: expected one of " + String.join(", ", DESTINATIONS));
            String practiceSlug = string(value.path("practiceSlug"));
            if (practiceSlug == null || Js.blank(practiceSlug)) issues.add("practiceSlug: expected a non-empty string");
            String action = string(value.path("action"));
            if (action == null || !ACTIONS.contains(action))
                issues.add("action: expected one of " + String.join(", ", ACTIONS));
            List<String> basedOn = List.of();
            if (!value.path("basedOn").isMissingNode()) {
                var ids = ReviewOnWork.ids(value.path("basedOn"));
                if (ids == null) issues.add("basedOn: expected an array of non-empty strings");
                else basedOn = ids;
            }
            List<String> witnessIds = null;
            if (!value.path("witnessIds").isMissingNode()) {
                witnessIds = ReviewOnWork.ids(value.path("witnessIds"));
                if (witnessIds == null) issues.add("witnessIds: expected an array of non-empty strings");
            }
            String withholdReason = null;
            JsonNode reason = value.path("withholdReason");
            if (!reason.isMissingNode() && !reason.isNull()) {
                withholdReason = string(reason);
                if (withholdReason == null || !ReviewOnWork.WITHHOLD_REASONS.contains(withholdReason))
                    issues.add("withholdReason: expected one of " + String.join(", ", ReviewOnWork.WITHHOLD_REASONS));
            }
            String supersedes = text(value, "supersedesThreadKey", issues, "supersedesThreadKey");
            String title = text(value, "title", issues, "title");
            String body = text(value, "body", issues, "body");
            String nextStep = text(value, "nextStep", issues, "nextStep");
            Map<String, String> notes = null;
            JsonNode notesNode = value.path("notes");
            if (!notesNode.isMissingNode() && !notesNode.isNull()) {
                if (!notesNode.isObject()) issues.add("notes: expected an object");
                else {
                    for (String key : notesNode.propertyNames())
                        if (!NOTE_FIELDS.contains(key)) issues.add("notes: unrecognized key \"" + key + "\"");
                    notes = new LinkedHashMap<>();
                    for (String field : NOTE_FIELDS) {
                        String note = text(notesNode, field, issues, "notes." + field);
                        if (note != null) notes.put(field, note);
                    }
                }
            }
            if (!issues.isEmpty()) return String.join("; ", issues);
            String slug = Js.trim(Objects.requireNonNull(practiceSlug));
            if (!practiceSlugs.contains(slug))
                return "practiceSlug '" + slug + "' is not a practice with an admitted result in this run (those are: "
                        + String.join(", ", practiceSlugs) + "); skipped.";
            if (basedOn.isEmpty()) {
                var ids = admission.results().stream()
                        .filter(result -> slug.equals(string(result.path("practiceSlug"))))
                        .map(result -> Evidence.text(result.path("resultId")))
                        .toList();
                return "basedOn is required: the admitted result or problem id(s) this piece rests on (" + slug
                        + "'s results: " + String.join(", ", ids) + "); skipped.";
            }
            return new Read(
                    Objects.requireNonNull(destination),
                    slug,
                    Objects.requireNonNull(action),
                    basedOn,
                    supersedes,
                    witnessIds,
                    withholdReason,
                    title,
                    body,
                    nextStep,
                    notes);
        }

        /** A trimmed optional text within its bound; empty is absent. */
        private static @Nullable String text(JsonNode piece, String field, List<String> issues, String label) {
            JsonNode value = piece.path(field);
            if (value.isMissingNode() || value.isNull()) return null;
            if (!value.isString()) {
                issues.add(label + ": expected a string");
                return null;
            }
            String trimmed = Js.trim(value.asString());
            String name = field;
            int max = bound(name);
            if (trimmed.length() > max) {
                var guidance = TEXT_GUIDANCE.get(name);
                issues.add(
                        guidance == null
                                ? label + ": at most " + max + " characters"
                                : label + ": Aim for at most " + guidance.target() + " characters; more than " + max
                                        + " is refused. Keep the evidence and its qualifications; remove repetition. "
                                        + "Corrected text example (synthetic; use this developer's supported facts): "
                                        + Js.quote(guidance.example()) + ". Send this piece again");
                return null;
            }
            return trimmed.isEmpty() ? null : trimmed;
        }

        private @Nullable String validate(Read piece) {
            String evidenceError = Evidence.validateFeedbackEvidence(piece.practiceSlug(), piece.basedOn(), results);
            if (evidenceError != null) return evidenceError;
            var named = Evidence.named(results);
            if (piece.destination().equals("PRACTICE_PAGE")
                    && piece.basedOn().stream()
                            .anyMatch(id -> !Objects.requireNonNull(named.get(id))
                                    .result()
                                    .practiceSlug()
                                    .equals(piece.practiceSlug())))
                return "A PRACTICE_PAGE card addresses one practice; basedOn must name only that practice's NOT_MET results or problems. Skipped.";
            if (piece.action().equals("WITHHOLD")) return validateWithhold(piece, named);
            if (piece.title() == null || Js.blank(piece.title()))
                return "A piece that is not a WITHHOLD needs a title; skipped.";
            if (piece.destination().equals("PRACTICE_PAGE") && piece.action().equals("SUPERSEDE"))
                return "A practice-page card replaces the open card automatically; use NEW and name no target. Skipped.";
            if (piece.action().equals("SUPERSEDE")) {
                if (piece.supersedesThreadKey() == null
                        || piece.supersedesThreadKey().isEmpty())
                    return "SUPERSEDE needs a supersedesThreadKey; skipped.";
                if (preparedTargets.stream()
                        .noneMatch(target -> target.threadKey().equals(piece.supersedesThreadKey())
                                && target.destination().equals(piece.destination())
                                && target.practiceSlug().equals(piece.practiceSlug())))
                    return "No queued message has threadKey '" + piece.supersedesThreadKey()
                            + "' on this destination and practice; it must come from the prepared-feedback file. Skipped.";
            }
            if (piece.destination().equals("CONVERSATION")) {
                if (piece.body() != null || piece.nextStep() != null)
                    return "CONVERSATION takes notes{situation,capability,evidenceSummary,inConversationLearning}, not body/nextStep - nothing for this destination is read out; skipped.";
                for (String field : List.of("situation", "capability", "evidenceSummary", "inConversationLearning"))
                    if (piece.notes() == null
                            || piece.notes().get(field) == null
                            || Js.blank(piece.notes().get(field)))
                        return "CONVERSATION needs notes." + field + "; skipped.";
                return null;
            }
            if (piece.notes() != null) return "Only CONVERSATION feedback may carry a notes block; skipped.";
            if (piece.nextStep() == null || Js.blank(piece.nextStep()))
                return piece.destination() + " needs a nextStep; skipped.";
            if (piece.body() == null || Js.blank(piece.body())) return "PRACTICE_PAGE needs a body; skipped.";
            String normalizedBody = normalizeQuotedText(piece.body());
            boolean repeats = piece.basedOn().stream().anyMatch(id -> {
                var entry = named.get(id);
                if (entry == null) return false;
                var result = admission.results().stream()
                        .filter(candidate -> Evidence.text(candidate.path("resultId"))
                                .equals(entry.ref().resultId()))
                        .findFirst()
                        .orElse(null);
                if (result == null) return false;
                var citations = new ArrayList<>(listOf(result.path("citations")));
                listOf(result.path("problems")).forEach(problem -> citations.addAll(listOf(problem.path("citations"))));
                return citations.stream().anyMatch(citation -> {
                    String quote = normalizeQuotedText(Objects.requireNonNullElse(string(citation.path("quote")), ""));
                    return quote.length() >= 12 && normalizedBody.contains(quote);
                });
            });
            return repeats
                    ? "PRACTICE_PAGE describes a pattern across items; do not copy a current item quote into it. Skipped."
                    : null;
        }

        private @Nullable String validateWithhold(Read piece, Map<String, Evidence.Named> named) {
            String reason = piece.withholdReason();
            if (reason == null || reason.isEmpty()) return "WITHHOLD needs a withholdReason; skipped.";
            boolean alreadySaid = reason.equals("ALREADY_SAID");
            var witnessedSlugs = new TreeSet<String>();
            piece.basedOn().forEach(id -> {
                var entry = named.get(id);
                if (entry != null && entry.result().notMet())
                    witnessedSlugs.add(entry.result().practiceSlug());
            });
            var unrecorded = witnessedSlugs.stream()
                    .filter(slug -> deliveredWitnesses.entrySet().stream()
                            .noneMatch(entry -> entry.getKey().startsWith(piece.destination() + ":" + slug + ":")
                                    && (alreadySaid
                                            ? entry.getValue().eligibleForAlreadySaid()
                                            : entry.getValue().eligibleForPriorAdvice())))
                    .toList();
            if (ReviewOnWork.PRIOR_ADVICE_REASONS.contains(reason) && !unrecorded.isEmpty()) {
                String needed = alreadySaid
                        ? "not withdrawn, with its words, delivered by the history read; its claim may be current or stale"
                        : "not withdrawn, with its words, its claim still current, delivered before this work was captured";
                return reason + " rests on feedback recorded as delivered on " + piece.destination() + " for "
                        + String.join(", ", unrecorded) + " in the delivered-feedback history: " + needed
                        + ", for each NOT_MET practice in basedOn. The history does not establish that prerequisite; prepared "
                        + "feedback and other destinations do not count. Decide this destination on its own evidence: the piece it supports, "
                        + "or WITHHOLD with BELOW_BAR. Skipped.";
            }
            var witnessIds = Objects.requireNonNullElse(piece.witnessIds(), List.<String>of());
            if (!reason.equals("BELOW_BAR") && witnessIds.isEmpty())
                return reason + " needs witnessIds from the delivered-feedback file; skipped.";
            BiPredicate<String, String> witnesses = (slug, id) -> {
                var witness = deliveredWitnesses.get(piece.destination() + ":" + slug + ":" + id);
                return witness != null
                        && (alreadySaid ? witness.eligibleForAlreadySaid() : witness.eligibleForPriorAdvice());
            };
            if (witnessIds.stream().anyMatch(id -> witnessedSlugs.stream().noneMatch(slug -> witnesses.test(slug, id)))
                    || witnessedSlugs.stream()
                            .anyMatch(slug -> !reason.equals("BELOW_BAR")
                                    && witnessIds.stream().noneMatch(id -> witnesses.test(slug, id))))
                return "Witnesses must name delivered feedback that can stand for " + reason
                        + " for every supported practice on this destination; skipped.";
            return null;
        }
    }

    private static String normalizeQuotedText(String value) {
        return Js.trim(Normalizer.normalize(value, Normalizer.Form.NFKC)
                        .replaceAll("[“”„‟]", "\"")
                        .replaceAll("[‘’‚‛]", "'")
                        .replaceAll("(?U)\\s+", " "))
                .toLowerCase(Locale.ROOT);
    }
}
