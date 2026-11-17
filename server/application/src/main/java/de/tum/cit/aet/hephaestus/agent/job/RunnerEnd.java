package de.tum.cit.aet.hephaestus.agent.job;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Why the review sandbox says its runner stopped, from the closed codes it records as {@code end} in runner-debug.json.
 * The sandbox writes that file, so only codes of the runner's own diagnostic vocabulary are shown.
 */
final class RunnerEnd {
    /** The vocabulary of the runner's diagnostics: the one home of its error kinds and stop reasons. */
    private static final String VOCABULARY = "agent/pi-diagnostics.json";

    private static final Set<String> ERRORS;
    private static final Set<String> REASONS;

    static {
        try (InputStream file =
                Objects.requireNonNull(RunnerEnd.class.getClassLoader().getResourceAsStream(VOCABULARY))) {
            JsonNode fields = new ObjectMapper().readTree(file).path("fields");
            ERRORS = values(fields.path("error"));
            REASONS = values(fields.path("reason"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private RunnerEnd() {}

    private static Set<String> values(JsonNode list) {
        Set<String> values = new HashSet<>();
        list.forEach(value -> values.add(value.asString()));
        return Set.copyOf(values);
    }

    /**
     * The sentence for a failed attempt's error; null when the runner recorded no reason that this exit carries. Only a
     * runner that exits with its own failure codes says why: 1 for no result, 2 for an unexpected error.
     */
    static @Nullable String describe(int exitCode, Map<String, Object> output) {
        if (!(output.get("runnerDebug") instanceof Map<?, ?> debug) || !(debug.get("end") instanceof Map<?, ?> end))
            return null;
        String event = String.valueOf(end.get("event"));
        @Nullable String error = known(end.get("error"), ERRORS);
        if (exitCode == 1 && event.equals("review.failed")) {
            return switch (String.valueOf(known(end.get("reason"), REASONS))) {
                case "no_practice" ->
                    error == null
                            ? "No practice produced a result."
                            : "No practice produced a result: a review session stopped on an unexpected error (" + error
                                    + ").";
                case "ceiling_before_start" -> "The attempt's time ran out before the review started.";
                default -> null;
            };
        }
        if (exitCode == 2 && (event.equals("review.fatal") || event.equals("review.unhandled_rejection")))
            return error == null
                    ? "The review sandbox stopped on an unexpected error."
                    : "The review sandbox stopped on an unexpected error (" + error + ").";
        return null;
    }

    private static @Nullable String known(@Nullable Object value, Set<String> vocabulary) {
        return value instanceof String code && vocabulary.contains(code) ? code : null;
    }
}
