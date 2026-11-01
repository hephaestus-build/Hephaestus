package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every point in time a Heph turn reads, in the developer's time zone with its weekday and offset, for example
 * {@code Mon 2026-10-12 14:41 +02:00}. Context documents carry UTC instants; a model that converts them itself, or
 * works out a weekday, gets the day or the hour wrong, so they are converted once here, for the whole document. Only timestamp fields are converted,
 * by their name; message bodies, comments, quotes and criteria stay exactly as their author wrote them.
 */
final class ContextTimes {

    /** A whole JSON string that is a UTC instant as {@link Instant#toString()} writes it. */
    private static final Pattern INSTANT =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?Z");

    /** Timestamp fields that do not end in {@code At}, the context documents' naming for the rest. */
    private static final Set<String> TIME_FIELDS =
            Set.of("at", "since", "until", "created", "updated", "dueOn", "milestoneDueOn", "windowStart", "windowEnd");

    private static final DateTimeFormatter LOCAL =
            DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm xxx", Locale.ENGLISH);

    private ContextTimes() {}

    /** A time as Heph reads it in {@code zone}. */
    static String local(Instant instant, ZoneId zone) {
        return LOCAL.format(instant.atZone(zone));
    }

    /**
     * {@code node} with the UTC instant of each timestamp field rewritten in {@code zone}. A field is a timestamp field
     * by its name ({@link #isTimeField}); any other value, text that looks like a time included, is left alone.
     */
    static JsonNode local(JsonNode node, ZoneId zone) {
        if (node instanceof ObjectNode object) {
            for (Map.Entry<String, JsonNode> field : object.properties()) {
                JsonNode value = field.getValue();
                if (value.isString()) {
                    if (isTimeField(field.getKey())) {
                        rewritten(value.asString(), zone).ifPresent(time -> object.put(field.getKey(), time));
                    }
                } else {
                    local(value, zone);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> local(element, zone));
        }
        return node;
    }

    static boolean isTimeField(String name) {
        return name.endsWith("At") || TIME_FIELDS.contains(name);
    }

    private static Optional<String> rewritten(String text, ZoneId zone) {
        if (!INSTANT.matcher(text).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(local(Instant.parse(text), zone));
        } catch (DateTimeException notAnInstant) {
            return Optional.empty();
        }
    }
}
