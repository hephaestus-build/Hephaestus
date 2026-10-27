package de.tum.cit.aet.hephaestus.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Writes the MDC and the event's key-value pairs as one nested {@code context} object, in place of Boot's own context
 * pairs ({@code logging.structured.json.context.include: false}).
 *
 * <p>Boot's ECS formatter refuses a name that the MDC and a key-value pair both carry and the appender then drops the
 * whole record. A lifecycle event that names its job inside that job's MDC scope was lost that way. Here the event's
 * own value replaces the MDC's, and a dotted name nests as ECS writes it.
 */
public class LogContextCustomizer implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add("context", LogContextCustomizer::context).whenNotEmpty();
    }

    static Map<String, Object> context(ILoggingEvent event) {
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) mdc.forEach((name, value) -> put(context, name, value));
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) pairs.forEach(pair -> put(context, pair.key, pair.value));
        return context;
    }

    /**
     * A later pair replaces an earlier one, also where one name is the prefix of another: no record is refused. A pair
     * without a value is left out, as Boot leaves out an empty member.
     */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> context, @Nullable String name, @Nullable Object value) {
        if (name == null || name.isEmpty() || value == null) return;
        Map<String, Object> destination = context;
        int start = 0;
        for (int dot = name.indexOf('.'); dot >= 0; dot = name.indexOf('.', start)) {
            String part = name.substring(start, dot);
            if (!(destination.get(part) instanceof Map<?, ?>)) {
                destination.put(part, new LinkedHashMap<String, Object>());
            }
            destination = (Map<String, Object>) destination.get(part);
            start = dot + 1;
        }
        destination.put(name.substring(start), value);
    }
}
