package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MergeReadinessContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.RecentAuthoredWorkContentSource;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** A bounded projection of the authorized turn map, not another read of the developer's work. */
final class MentorTurnEvidence {

    private static final int MAX_SOURCE_CHARS = 12_000;
    private static final List<String> WORK_FIELDS =
            List.of("artifactId", "resource", "number", "url", "state", "isDraft");
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
        ObjectNode readiness = source(
                mapper,
                inputs,
                MergeReadinessContentSource.OUTPUT_KEY,
                List.of("pullRequests", "notLoaded"),
                READINESS_FIELDS);
        receipt.set("mergeReadiness", readiness);

        ObjectNode observations = source(
                mapper,
                inputs,
                ObservationHistoryContentSource.OUTPUT_KEY,
                List.of("recentObservations", "abstentions", "earlierObservations"),
                OBSERVATION_FIELDS);
        receipt.set("observations", observations);

        ObjectNode authored = source(
                mapper,
                inputs,
                RecentAuthoredWorkContentSource.OUTPUT_KEY,
                List.of("pullRequests", "issues"),
                WORK_FIELDS);
        authored.put("use", "ARTIFACT_INDEX_NOT_CURRENT_READINESS");
        receipt.set("authoredWorkIndex", authored);
        return mapper.writeValueAsString(receipt);
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
