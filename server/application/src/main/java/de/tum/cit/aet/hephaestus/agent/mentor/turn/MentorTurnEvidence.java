package de.tum.cit.aet.hephaestus.agent.mentor.turn;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ObservationHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.RecentAuthoredWorkContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.ReviewAttemptsContentSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The index a turn's situation carries: what the turn can read, each row with its number or practice, title, state,
 * date and {@code resource}. It names; it does not tell. A row carries no discussion, no checks and no evidence, so an
 * answer about the work needs a read of the work.
 */
public final class MentorTurnEvidence {

    /** The index's bound, in UTF-16 units: it rides on every model request of the turn, about 2,000 tokens. */
    static final int MAX_RECEIPT_CHARS = 7_000;

    private static final int MAX_TITLE_CHARS = 80;

    /** One list of the index: where its rows come from and what each keeps. */
    private record Listing(String name, String key, String sourceList, int maxRows, List<String> fields) {}

    private static final List<Listing> LISTINGS = List.of(
            new Listing(
                    "pullRequests",
                    RecentAuthoredWorkContentSource.OUTPUT_KEY,
                    "pullRequests",
                    8,
                    List.of("number", "title", "state", "updatedAt", "url", "resource")),
            new Listing(
                    "issues",
                    RecentAuthoredWorkContentSource.OUTPUT_KEY,
                    "issues",
                    8,
                    List.of("number", "title", "state", "updatedAt", "url", "resource")),
            new Listing(
                    "observations",
                    ObservationHistoryContentSource.OUTPUT_KEY,
                    "recentObservations",
                    10,
                    List.of("practiceSlug", "outcome", "observedAt", "work", "resource")),
            new Listing(
                    "reviewAttempts",
                    ReviewAttemptsContentSource.OUTPUT_KEY,
                    "attempts",
                    6,
                    List.of("number", "title", "status", "completedAt", "url", "reviewsResource")));

    private MentorTurnEvidence() {}

    /** The index of a turn's context inputs, at most {@link #MAX_RECEIPT_CHARS} long. */
    public static String receipt(ObjectMapper mapper, Map<String, byte[]> inputs) {
        ObjectNode index = mapper.createObjectNode();
        index.put(
                "use", "What this turn can read, not what it says. Fetch a row's resource before you answer about it.");
        ObjectNode omitted = mapper.createObjectNode();
        ArrayNode unavailable = mapper.createArrayNode();
        for (Listing listing : LISTINGS) {
            Optional<JsonNode> source = read(mapper, inputs, listing.key());
            if (source.isEmpty()) {
                if (!contains(unavailable, listing.key())) {
                    unavailable.add(listing.key());
                }
                continue;
            }
            ArrayNode rows = index.putArray(listing.name());
            JsonNode sourceRows = source.get().path(listing.sourceList());
            for (JsonNode row : sourceRows) {
                if (rows.size() == listing.maxRows()) {
                    count(omitted, listing.name());
                    continue;
                }
                rows.add(project(mapper, row, listing.fields()));
            }
        }
        if (!unavailable.isEmpty()) {
            index.set("unavailable", unavailable);
        }
        fit(mapper, index, omitted);
        if (!omitted.isEmpty()) {
            index.set("omitted", omitted);
        }
        return mapper.writeValueAsString(index);
    }

    /** Leaves out the last row of the longest list until the index fits, counting each in {@code omitted}. */
    private static void fit(ObjectMapper mapper, ObjectNode index, ObjectNode omitted) {
        while (mapper.writeValueAsString(index).length()
                        + mapper.writeValueAsString(omitted).length()
                > MAX_RECEIPT_CHARS) {
            List<String> lists = new ArrayList<>();
            for (Listing listing : LISTINGS) {
                if (index.get(listing.name()) instanceof ArrayNode rows && !rows.isEmpty()) {
                    lists.add(listing.name());
                }
            }
            if (lists.isEmpty()) {
                return;
            }
            String longest = lists.stream()
                    .max(Comparator.comparingInt(name -> index.get(name).size()))
                    .orElseThrow();
            ArrayNode rows = (ArrayNode) index.get(longest);
            rows.remove(rows.size() - 1);
            count(omitted, longest);
        }
    }

    private static ObjectNode project(ObjectMapper mapper, JsonNode row, List<String> fields) {
        ObjectNode entry = mapper.createObjectNode();
        for (String field : fields) {
            JsonNode value = row.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (field.equals("title") && value.isString()) {
                entry.put(field, shortened(value.asString()));
            } else if (field.equals("work") && value.isObject()) {
                // The pull request or issue an observation is about, by number only.
                if (value.path("number").isNumber()) {
                    entry.put("workNumber", value.path("number").asInt());
                }
            } else {
                entry.set(field, value.deepCopy());
            }
        }
        return entry;
    }

    private static String shortened(String title) {
        if (title.length() <= MAX_TITLE_CHARS) {
            return title;
        }
        int end = MAX_TITLE_CHARS - 1;
        if (Character.isHighSurrogate(title.charAt(end - 1))) {
            end--;
        }
        return title.substring(0, end) + "…";
    }

    private static Optional<JsonNode> read(ObjectMapper mapper, Map<String, byte[]> inputs, String key) {
        byte[] bytes = inputs.get(key);
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = mapper.readTree(bytes);
            return parsed instanceof ObjectNode ? Optional.of(parsed) : Optional.empty();
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
    }

    private static void count(ObjectNode omitted, String list) {
        omitted.put(list, omitted.path(list).asInt() + 1);
    }

    private static boolean contains(ArrayNode array, String value) {
        for (JsonNode element : array) {
            if (element.asString().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
