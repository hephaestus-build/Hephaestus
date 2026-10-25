package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.Js.isRecord;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.listOf;
import static de.tum.cit.aet.hephaestus.agent.composer.Js.string;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The admitted results of one review and the server's reads that route its private feedback, in the shape the
 * composers read them: a port of the runner's {@code parseAdmission}, with progress kept per private destination.
 *
 * @param results each admitted result with its citations lifted beside its evidence and each problem's id as
 *     {@code problemId}, in the runner's key order
 * @param decided the practices the review's prepared private pieces already decide, per private destination
 * @param deliveries the room those pieces used, per private destination
 * @param practicePageSupport the page support read, or null when it is missing or does not match the results
 * @param conversationEligibility the conversation routing read, or null when it is missing or does not match
 * @param privateHistory the bounded read of earlier private feedback, or null when it was not read
 */
record Admission(
        List<ObjectNode> results,
        String digest,
        boolean publicReviewed,
        Map<String, Set<String>> decided,
        Map<String, Integer> deliveries,
        @Nullable JsonNode practicePageSupport,
        @Nullable JsonNode conversationEligibility,
        @Nullable JsonNode privateHistory) {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private static final List<String> PRACTICE_PAGE_DECISIONS = List.of(
            "ADMIT",
            "FEEDBACK_OFF",
            "GATE_REFUSED",
            "NO_EVIDENCE",
            "UNCORROBORATED",
            "BACKFILL_HELD",
            "RECENTLY_SURFACED",
            "UNATTRIBUTABLE_SUBJECT");

    private static final List<String> CONVERSATION_DECISIONS = List.of(
            "ADMIT",
            "FEEDBACK_OFF",
            "MENTOR_UNAVAILABLE",
            "GATE_REFUSED",
            "UNATTRIBUTABLE_SUBJECT",
            "HAS_INLINE_ANCHOR",
            "ALREADY_DELIVERED_ON_WORK",
            "REVIEWER_DEFERRED",
            "BACKFILL_QUIET");

    /**
     * Reads the admission answer as the server writes it.
     *
     * @param response the admission answer's JSON
     * @param decidedResults the admitted results each private destination's prepared pieces already decide
     */
    static Admission of(JsonNode response, Map<String, Set<String>> decidedResults) {
        var results = new ArrayList<ObjectNode>();
        for (JsonNode admitted : listOf(response.path("admitted"))) results.add(normalize(admitted));
        var decided = Map.<String, Set<String>>of(
                "PRACTICE_PAGE", practicesOf(results, decidedResults.getOrDefault("PRACTICE_PAGE", Set.of())),
                "CONVERSATION", practicesOf(results, decidedResults.getOrDefault("CONVERSATION", Set.of())));
        JsonNode progress = response.path("privateProgress");
        JsonNode history = response.path("privateHistory");
        return new Admission(
                results,
                Evidence.text(response.path("admissionDigest")),
                ReviewOnWork.isTrue(response.path("publicReviewed")),
                decided,
                Map.of(
                        "PRACTICE_PAGE", progress.path("practicePageDeliveries").asInt(0),
                        "CONVERSATION", progress.path("conversationDeliveries").asInt(0)),
                practicePageSupport(response.path("practicePageSupport"), results),
                conversationEligibility(response.path("conversationEligibility"), results),
                history.isObject() ? history : null);
    }

    private static Set<String> practicesOf(List<ObjectNode> results, Set<String> resultIds) {
        var practices = new TreeSet<String>();
        for (var result : results)
            if (resultIds.contains(Evidence.text(result.path("resultId"))))
                practices.add(Evidence.text(result.path("practiceSlug")));
        return practices;
    }

    /**
     * One admitted result as the runner's schema returns it: its declared fields first, then the others as sent, then
     * its evidence, the evidence's citations each with their index, and its problems with their id last.
     */
    static ObjectNode normalize(JsonNode admitted) {
        var result = JSON.objectNode();
        for (String key : List.of("resultId", "practiceSlug", "outcome"))
            if (admitted.has(key)) result.set(key, admitted.path(key));
        admitted.properties().forEach(property -> {
            if (!Set.of("resultId", "practiceSlug", "outcome", "evidence", "problems")
                    .contains(property.getKey())) result.set(property.getKey(), property.getValue());
        });
        JsonNode sentEvidence = admitted.path("evidence");
        var evidence = JSON.objectNode();
        var citations = indexed(sentEvidence.path("citations"));
        evidence.set("citations", citations);
        sentEvidence.properties().forEach(property -> {
            if (!property.getKey().equals("citations")) evidence.set(property.getKey(), property.getValue());
        });
        result.set("evidence", evidence);
        result.set("citations", citations);
        var problems = result.putArray("problems");
        for (JsonNode sent : listOf(admitted.path("problems"))) {
            var problem = JSON.objectNode();
            problem.set("citations", indexed(sent.path("citations")));
            sent.properties().forEach(property -> {
                if (!Set.of("id", "citations").contains(property.getKey()))
                    problem.set(property.getKey(), property.getValue());
            });
            problem.set("problemId", sent.path("id"));
            problems.add(problem);
        }
        return result;
    }

    private static ArrayNode indexed(JsonNode citations) {
        var list = JSON.arrayNode();
        int index = 0;
        for (JsonNode citation : listOf(citations)) {
            var copy = citation.isObject() ? ((ObjectNode) citation).deepCopy() : JSON.objectNode();
            copy.put("index", index++);
            list.add(copy);
        }
        return list;
    }

    /** The page support read when it covers exactly the NOT_MET practices and admits only with occurrences. */
    private static @Nullable JsonNode practicePageSupport(JsonNode value, List<ObjectNode> results) {
        if (!isRecord(value) || !value.path("readAt").isString()) return null;
        String state = string(value.path("state"));
        if ("UNAVAILABLE".equals(state) || "REFUSED".equals(state)) return value;
        if (!"COMPLETE".equals(state) || !value.path("practices").isArray()) return null;
        var expected = new HashSet<String>();
        for (var result : results)
            if ("NOT_MET".equals(string(result.path("outcome"))))
                expected.add(Evidence.text(result.path("practiceSlug")));
        for (JsonNode practice : value.path("practices")) {
            String decision = string(practice.path("decision"));
            if (decision == null
                    || !PRACTICE_PAGE_DECISIONS.contains(decision)
                    || (decision.equals("GATE_REFUSED")
                            && practice.path("stopReason").isNull())
                    || !expected.remove(Evidence.text(practice.path("practiceSlug")))
                    || (!decision.equals("ADMIT")
                            && !practice.path("occurrences").isEmpty())) return null;
        }
        return expected.isEmpty() ? value : null;
    }

    /** The conversation routing read when it decides exactly the NOT_MET results. */
    private static @Nullable JsonNode conversationEligibility(JsonNode value, List<ObjectNode> results) {
        if (!isRecord(value) || !value.path("readAt").isString()) return null;
        String state = string(value.path("state"));
        if ("UNAVAILABLE".equals(state) || "REFUSED".equals(state)) {
            var closed = JSON.objectNode();
            closed.put("state", state);
            closed.set("readAt", value.path("readAt"));
            return closed;
        }
        if (!"COMPLETE".equals(state) || !value.path("results").isArray()) return null;
        var expected = new HashSet<String>();
        for (var result : results)
            if ("NOT_MET".equals(string(result.path("outcome")))) expected.add(Evidence.text(result.path("resultId")));
        for (JsonNode decision : value.path("results")) {
            String chosen = string(decision.path("decision"));
            if (chosen == null
                    || !CONVERSATION_DECISIONS.contains(chosen)
                    || (chosen.equals("GATE_REFUSED")
                            && decision.path("stopReason").isNull())
                    || !expected.remove(Evidence.text(decision.path("resultId")))) return null;
        }
        return expected.isEmpty() ? value : null;
    }

    boolean complete(String destination) {
        JsonNode read = destination.equals("PRACTICE_PAGE") ? practicePageSupport : conversationEligibility;
        return read != null && "COMPLETE".equals(string(read.path("state")));
    }

    /** Whether the server routes this practice's NOT_MET result to the destination. */
    boolean routedTo(String destination, String practiceSlug) {
        if (destination.equals("PRACTICE_PAGE"))
            return complete(destination)
                    && listOf(practicePageSupport == null ? JSON.missingNode() : practicePageSupport.path("practices"))
                            .stream()
                            .anyMatch(practice -> practiceSlug.equals(string(practice.path("practiceSlug")))
                                    && "ADMIT".equals(string(practice.path("decision"))));
        if (!complete(destination) || conversationEligibility == null) return false;
        var admitted = new HashSet<String>();
        for (JsonNode route : conversationEligibility.path("results"))
            if ("ADMIT".equals(string(route.path("decision")))) admitted.add(Evidence.text(route.path("resultId")));
        return results.stream()
                .anyMatch(result -> practiceSlug.equals(string(result.path("practiceSlug")))
                        && "NOT_MET".equals(string(result.path("outcome")))
                        && admitted.contains(Evidence.text(result.path("resultId"))));
    }

    /** Why the destination cannot carry this practice, for a refusal the composer can act on. */
    String destinationRefusal(String destination, String practiceSlug) {
        if (decided.getOrDefault(destination, Set.of()).contains(practiceSlug))
            return "already decided by private feedback an earlier attempt of this review prepared";
        if (destination.equals("PRACTICE_PAGE")) {
            if (!complete(destination) || practicePageSupport == null) return "not routed";
            return listOf(practicePageSupport.path("practices")).stream()
                    .filter(practice -> practiceSlug.equals(string(practice.path("practiceSlug"))))
                    .map(practice -> Evidence.text(practice.path("decision")))
                    .findFirst()
                    .orElse("not routed");
        }
        var ids = new HashSet<String>();
        for (var result : results)
            if (practiceSlug.equals(string(result.path("practiceSlug"))))
                ids.add(Evidence.text(result.path("resultId")));
        var decisions = new LinkedHashSet<String>();
        if (complete(destination) && conversationEligibility != null)
            for (JsonNode route : conversationEligibility.path("results"))
                if (ids.contains(Evidence.text(route.path("resultId"))))
                    decisions.add(Evidence.text(route.path("decision")));
        return decisions.isEmpty() ? "not routed" : String.join(", ", decisions);
    }

    /** The distinct pieces of work a new practice-page card for the practice may cite. */
    int negativePiecesOfWork(String practiceSlug) {
        if (!complete("PRACTICE_PAGE") || practicePageSupport == null) return 0;
        var pieces = new HashSet<String>();
        listOf(practicePageSupport.path("practices")).stream()
                .filter(practice -> practiceSlug.equals(string(practice.path("practiceSlug"))))
                .findFirst()
                .ifPresent(practice -> listOf(practice.path("occurrences"))
                        .forEach(occurrence -> pieces.add(Evidence.text(occurrence.path("itemKind")) + ":"
                                + Evidence.text(occurrence.path("itemId")))));
        return pieces.size();
    }

    /** The practices each private destination's prepared pieces already decide, together, sorted. */
    List<String> decidedPractices() {
        var all = new TreeSet<String>();
        decided.values().forEach(all::addAll);
        return List.copyOf(all);
    }
}
