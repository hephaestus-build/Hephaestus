package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MergeReadinessContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.RecentAuthoredWorkContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ReviewAttemptsContentSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A bounded projection of the authorized turn map, not another read of the developer's work. */
final class MentorTurnEvidence {

    /**
     * The {@code currentEvidence} length {@code handlePrompt} in {@code pi-mentor-runner.ts} accepts; a longer receipt
     * is refused before the turn starts. Both sides count UTF-16 units.
     */
    static final int MAX_RECEIPT_CHARS = 40_000;

    private static final int MAX_SOURCE_CHARS = 12_000;
    private static final List<String> WORK_FIELDS =
            List.of("artifactId", "resource", "reviewsResource", "number", "url", "state", "isDraft");
    private static final List<String> ATTEMPT_FIELDS = List.of(
            "reviewId",
            "artifactKind",
            "artifactId",
            "number",
            "url",
            "state",
            "reviewsResource",
            "status",
            "triggerMode",
            "createdAt",
            "completedAt");
    private static final List<String> READINESS_FIELDS = List.of(
            "artifactId",
            "resource",
            "number",
            "url",
            "state",
            "isDraft",
            "isMerged",
            "mergedAt",
            "mergedBy",
            "mergedByBot",
            "recordUpdatedAt",
            "mergeable",
            "mergeStateStatus",
            "reviewDecision",
            "headSha",
            "checks",
            "checksFor",
            "checksSha",
            "checksObserved",
            "checksObservedAt",
            "latestReviewsStatus");
    private static final List<String> OBSERVATION_FIELDS = List.of(
            "id",
            "resource",
            "reviewId",
            "origin",
            "practiceSlug",
            "outcome",
            "severity",
            "observedAt",
            "artifactKind",
            "artifactId",
            "reviewedWork");

    private MentorTurnEvidence() {}

    static String forRunner(ObjectMapper mapper, Map<String, byte[]> inputs) {
        ObjectNode receipt = mapper.createObjectNode();
        receipt.put("providerFreshness", "UNKNOWN");
        List<String> readinessLists = List.of("pullRequests", "notLoaded");
        ObjectNode readiness =
                source(mapper, inputs, MergeReadinessContentSource.OUTPUT_KEY, readinessLists, READINESS_FIELDS);
        receipt.set("mergeReadiness", readiness);

        List<String> observationLists = List.of("recentObservations", "abstentions", "earlierObservations");
        ObjectNode observations = source(
                mapper, inputs, ObservationHistoryContentSource.OUTPUT_KEY, observationLists, OBSERVATION_FIELDS);
        receipt.set("observations", observations);

        List<String> workLists = List.of("pullRequests", "issues");
        ObjectNode authored =
                source(mapper, inputs, RecentAuthoredWorkContentSource.OUTPUT_KEY, workLists, WORK_FIELDS);
        authored.put("use", "ARTIFACT_INDEX_NOT_CURRENT_READINESS");
        receipt.set("authoredWorkIndex", authored);

        List<String> attemptLists = List.of("attempts");
        ObjectNode attempts =
                source(mapper, inputs, ReviewAttemptsContentSource.OUTPUT_KEY, attemptLists, ATTEMPT_FIELDS);
        receipt.set("reviewAttempts", attempts);

        return fitted(
                mapper,
                receipt,
                List.of(
                        new Section(readiness, readinessLists),
                        new Section(observations, observationLists),
                        new Section(authored, workLists),
                        new Section(attempts, attemptLists)));
    }

    /** One projected source and the names of its row lists. */
    private record Section(ObjectNode node, List<String> lists) {}

    /**
     * Fits the whole receipt within {@link #MAX_RECEIPT_CHARS}: each section is bounded on its own, but together they
     * can exceed what the runner accepts. Leaves out whole rows, the last row of the largest section first, and counts
     * each in that section's {@code omittedFromReceipt}. A receipt whose metadata alone does not fit is unavailable,
     * never a shorter one that reads as complete.
     */
    private static String fitted(ObjectMapper mapper, ObjectNode receipt, List<Section> sections) {
        String serialized = mapper.writeValueAsString(receipt);
        while (serialized.length() > MAX_RECEIPT_CHARS) {
            Section largest = null;
            int largestLength = -1;
            for (Section section : sections) {
                int length = mapper.writeValueAsString(section.node()).length();
                if (lastNonEmptyList(section) != null && length > largestLength) {
                    largest = section;
                    largestLength = length;
                }
            }
            if (largest == null) {
                return mapper.writeValueAsString(mapper.createObjectNode()
                        .put("providerFreshness", "UNKNOWN")
                        .put("status", "UNAVAILABLE")
                        .put(
                                "reason",
                                "This turn's stored evidence does not fit in one message; fetch its resources."));
            }
            String list = Objects.requireNonNull(lastNonEmptyList(largest));
            ArrayNode rows = (ArrayNode) largest.node().get(list);
            rows.remove(rows.size() - 1);
            ObjectNode omitted = largest.node().withObject("omittedFromReceipt");
            omitted.put(list, omitted.path(list).asInt() + 1);
            serialized = mapper.writeValueAsString(receipt);
        }
        return serialized;
    }

    private static @Nullable String lastNonEmptyList(Section section) {
        for (String list : section.lists().reversed()) {
            if (section.node().get(list) instanceof ArrayNode rows && !rows.isEmpty()) {
                return list;
            }
        }
        return null;
    }

    private static ObjectNode source(
            ObjectMapper mapper, Map<String, byte[]> inputs, String key, List<String> arrays, List<String> fields) {
        ObjectNode selected = mapper.createObjectNode().put("resource", key);
        byte[] bytes = inputs.get(key);
        if (bytes == null) {
            return selected.put("status", "UNAVAILABLE");
        }
        try {
            JsonNode parsed = mapper.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) {
                return selected.put("status", "UNAVAILABLE");
            }
            copy(
                    object,
                    selected,
                    List.of(
                            "_meta",
                            "status",
                            "readAt",
                            "providerFreshness",
                            "coverage",
                            "omittedForSize",
                            "sizeLimited",
                            "notLoadedTruncated"));
            for (String name : arrays) {
                if (object.path(name).isArray()) {
                    project(mapper, selected, object.path(name), name, fields);
                }
            }
            return selected;
        } catch (JacksonException malformed) {
            return selected.put("status", "UNAVAILABLE");
        }
    }

    private static void project(
            ObjectMapper mapper, ObjectNode source, JsonNode rows, String name, List<String> fields) {
        var selected = source.putArray(name);
        int leftOut = 0;
        for (JsonNode row : rows) {
            ObjectNode entry = mapper.createObjectNode();
            copy(row, entry, fields);
            if (fields.contains("latestReviewsStatus")
                    && row.path("latestReviews").isArray()) {
                var reviews = entry.putArray("latestReviews");
                for (JsonNode review : row.path("latestReviews")) {
                    copy(
                            review,
                            reviews.addObject(),
                            List.of(
                                    "reviewer",
                                    "bot",
                                    "state",
                                    "dismissedState",
                                    "submittedAt",
                                    "commit",
                                    "commitFor"));
                }
            }
            selected.add(entry);
            if (mapper.writeValueAsString(source).length() > MAX_SOURCE_CHARS) {
                selected.remove(selected.size() - 1);
                leftOut++;
            }
        }
        if (leftOut > 0) {
            source.withObject("omittedFromReceipt").put(name, leftOut);
        }
    }

    private static void copy(JsonNode source, ObjectNode into, List<String> fields) {
        for (String field : fields) {
            JsonNode value = source.get(field);
            if (value != null) {
                into.set(field, value.deepCopy());
            }
        }
    }
}
