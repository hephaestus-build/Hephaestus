package de.tum.cit.aet.hephaestus.integration.core.spi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;

public record ItemEventName(String value) {
    public static final int MAX_LENGTH = 128;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ItemEventName {
        Objects.requireNonNull(value);
        if (value.length() > MAX_LENGTH || !value.matches("[a-z][a-z0-9_]*[.][a-z][a-z0-9_]*[.][a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("Invalid item event name");
    }

    /**
     * The follow-up occasion of a review that ended owing practices: Hephaestus records it, no provider does. It selects
     * no practice itself, so its readiness edge measures only the carried ones.
     */
    public static ItemEventName carried(ItemKind kind) {
        return new ItemEventName(kind.value() + ".carried");
    }

    @JsonValue
    @Override
    public String value() {
        return value;
    }
}
