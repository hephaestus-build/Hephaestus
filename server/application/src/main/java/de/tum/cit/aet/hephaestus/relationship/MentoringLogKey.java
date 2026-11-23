package de.tum.cit.aet.hephaestus.relationship;

import java.time.Duration;
import java.util.UUID;

/**
 * The closed list of fields that a mentoring record may carry, each with the one class of value it takes. None names a
 * person, so a record joins no person to a decision. A producer that needs another field adds it here, with its value
 * class: a row identifier, a count, a duration, a flag, or one enum of codes.
 */
public enum MentoringLogKey {
    WORKSPACE_ID("workspace.id", Long.class),
    RUN_ID("run.id", UUID.class),
    RUN_EPOCH("run.epoch", Integer.class),
    DURATION("mentoring.duration_ms", Duration.class);

    private final String key;
    private final Class<?> type;

    MentoringLogKey(String key, Class<?> type) {
        this.key = key;
        this.type = type;
    }

    /** The structured log key. */
    public String key() {
        return key;
    }

    /** The one class of value that the field takes. */
    public Class<?> type() {
        return type;
    }
}
