package de.tum.cit.aet.hephaestus.core.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Renders English fallback text at the server and provider boundaries. */
public final class MentoringCopy {

    private static final Map<String, String> ENGLISH = catalog("en");

    private MentoringCopy() {}

    public static LabelDTO label(String code) {
        return new LabelDTO(code, null, text(code));
    }

    /** In-progress snapshots written before stage codes carry fallback text directly. */
    public static LabelDTO progressLabel(String stage) {
        String unknown = "conversation.stage.unknown";
        if (stage.startsWith("conversation.stage.")) {
            return new LabelDTO(stage, null, ENGLISH.getOrDefault(stage, text(unknown)));
        }
        return new LabelDTO(unknown, null, stage);
    }

    public static String text(String code) {
        String copy = ENGLISH.get(code);
        if (copy == null) throw new IllegalArgumentException("Unknown mentoring copy code: " + code);
        return copy;
    }

    public static String code(String prefix, Enum<?> value) {
        return prefix + value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Map<String, String> catalog(String language) {
        try (var input = new ClassPathResource("copy/mentoring." + language + ".json").getInputStream()) {
            return Map.copyOf(JsonMapper.shared().readValue(input, new TypeReference<Map<String, String>>() {}));
        } catch (IOException error) {
            throw new UncheckedIOException("Could not load the mentoring copy catalog", error);
        }
    }
}
