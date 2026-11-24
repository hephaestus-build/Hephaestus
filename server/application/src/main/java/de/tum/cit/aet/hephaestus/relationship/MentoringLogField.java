package de.tum.cit.aet.hephaestus.relationship;

import de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.Sink;
import java.time.Duration;
import java.util.UUID;

/** One mentoring field. The sink checks its class before it encodes the value. */
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
        var type = key.type();
        if (type == null || !type.isInstance(value))
            throw new IllegalArgumentException("Invalid explicit value for mentoring field " + key);
        return new MentoringLogField(key, value);
    }

    String key() {
        return key.key();
    }

    Object valueFor(Sink sink) {
        key.fieldClass().requireAllowed(sink);
        return switch (value) {
            case UUID id -> id.toString();
            case Duration duration -> duration.toMillis();
            // A code is the constant's name, never its toString.
            case Enum<?> code -> code.name();
            case Boolean flag -> flag;
            case Integer count -> count;
            case Long number -> number;
            default -> throw new IllegalArgumentException("Unsupported mentoring field type");
        };
    }

    /** The label of an instance-wide meter uses a closed enum value. */
    String metricLabel() {
        key.fieldClass().requireAllowed(Sink.METRIC_LABEL);
        if (value instanceof Enum<?> code) return code.name();
        throw new IllegalArgumentException("A mentoring metric label must have a bounded value");
    }
}
