package de.tum.cit.aet.hephaestus.agent.practice.live;

import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/** Reads the optional system properties that select how a live measurement runs. */
final class LiveProperty {

    private LiveProperty() {}

    /** The positive {@code int} that {@code value} names; absent when the value is. Anything else fails. */
    static OptionalInt positiveInt(String name, @Nullable String value) {
        if (value == null) {
            return OptionalInt.empty();
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer, not '" + value + "'", e);
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be a positive integer, not '" + value + "'");
        }
        return OptionalInt.of(parsed);
    }
}
