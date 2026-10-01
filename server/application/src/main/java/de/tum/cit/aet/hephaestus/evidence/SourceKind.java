package de.tum.cit.aet.hephaestus.evidence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable, contract-scoped identifier for one logical source. */
public record SourceKind(String value) implements Comparable<SourceKind> {
    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SourceKind {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            // The author may have typed it, so it says what an id looks like rather than only that this is not one.
            throw new IllegalArgumentException(
                    value + " is not an evidence source id. An id is lower-case words joined by dots, such as "
                            + "scm.pull-request.diff.");
        }
    }

    @Override
    @JsonValue
    public String value() {
        return value;
    }

    @Override
    public int compareTo(SourceKind other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
