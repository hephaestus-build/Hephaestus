package de.tum.cit.aet.hephaestus.agent.sandbox.kubernetes;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review runtime's diagnostic vocabulary, read from the file that the runtime writes with ({@value #RESOURCE}):
 * its events, and for each field the values that it allows. A line that uses anything else can quote reviewed code, a
 * tool result, a provider's message or a credential, also when an older runtime image wrote it, so it is not kept.
 */
final class DiagnosticVocabulary {

    static final String RESOURCE = "agent/pi-diagnostics.json";

    /** The text that each scalar type allows. {@code pi-diagnostics.ts} holds the same three patterns. */
    static final Map<String, Pattern> SCALAR_TEXT = Map.of(
            "count", Pattern.compile("\\d{1,16}"),
            "ratio", Pattern.compile("\\d{1,6}(?:\\.\\d{1,6})?"),
            "flag", Pattern.compile("(?:true|false)"));

    private static final Pattern LINE = Pattern.compile("\\[pi-(?:runner|provider)\\] (\\S.*)");

    private final Set<String> events;
    private final Map<String, Predicate<String>> fields;

    private DiagnosticVocabulary(Set<String> events, Map<String, Predicate<String>> fields) {
        this.events = events;
        this.fields = fields;
    }

    static DiagnosticVocabulary load() {
        try (InputStream in = DiagnosticVocabulary.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is not on the classpath");
            JsonNode root = JsonMapper.builder().build().readTree(in);
            Set<String> events = new HashSet<>();
            root.path("events").forEach(event -> events.add(event.asString()));
            Map<String, Predicate<String>> fields = new HashMap<>();
            for (Map.Entry<String, JsonNode> field : root.path("fields").properties()) {
                fields.put(field.getKey(), allowed(field.getKey(), field.getValue()));
            }
            return new DiagnosticVocabulary(Set.copyOf(events), Map.copyOf(fields));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
    }

    private static Predicate<String> allowed(String key, JsonNode type) {
        if (type.isArray()) {
            Set<String> codes = new HashSet<>();
            type.forEach(code -> codes.add(code.asString()));
            return Set.copyOf(codes)::contains;
        }
        Pattern scalar = SCALAR_TEXT.get(type.asString());
        if (scalar == null) throw new IllegalStateException(RESOURCE + ": field " + key + " has no type");
        return text -> scalar.matcher(text).matches();
    }

    /**
     * Whether {@code line} is one event of the vocabulary, then fields of the vocabulary, each once, each with a value
     * that its type allows. {@code isDiagnosticLine} in {@code pi-diagnostics.ts} decides the same way.
     */
    boolean keeps(String line) {
        var matcher = LINE.matcher(line);
        if (!matcher.matches()) return false;
        String[] parts = matcher.group(1).split(" ", -1);
        if (!events.contains(parts[0])) return false;
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < parts.length; i++) {
            int separator = parts[i].indexOf('=');
            if (separator < 1) return false;
            String key = parts[i].substring(0, separator);
            Predicate<String> allowed = fields.get(key);
            if (allowed == null || !seen.add(key) || !allowed.test(parts[i].substring(separator + 1))) return false;
        }
        return true;
    }
}
