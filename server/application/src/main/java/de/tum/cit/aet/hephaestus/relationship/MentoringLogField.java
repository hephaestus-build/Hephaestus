package de.tum.cit.aet.hephaestus.relationship;

import java.time.Duration;
import java.util.UUID;

/**
 * One field of a mentoring record. Its value has the class that its key declares ({@link MentoringLogKey#type}): a row
 * identifier, a count, a duration, a flag or a code of one enum. No key takes text, so no message, prompt, answer or
 * name reaches a record through a field.
 */
public final class MentoringLogField {

    private final MentoringLogKey key;
    private final Object value;

    private MentoringLogField(MentoringLogKey key, Object value) {
        this.key = key;
        this.value = value;
    }

    /**
     * The field {@code key} with {@code value}.
     *
     * @throws IllegalArgumentException when {@code value} is not of the class that {@code key} declares
     */
    public static MentoringLogField of(MentoringLogKey key, Object value) {
        if (!key.type().isInstance(value))
            throw new IllegalArgumentException(key + " takes a " + key.type().getSimpleName());
        Object written = switch (value) {
            case UUID id -> id.toString();
            case Duration duration -> duration.toMillis();
            // A code is the constant's name, never its toString.
            case Enum<?> code -> code.name();
            default -> value;
        };
        return new MentoringLogField(key, written);
    }

    String key() {
        return key.key();
    }

    Object value() {
        return value;
    }
}
