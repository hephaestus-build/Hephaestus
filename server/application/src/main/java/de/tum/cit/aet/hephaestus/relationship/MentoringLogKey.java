package de.tum.cit.aet.hephaestus.relationship;

import static de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.CORRELATION_HANDLE;
import static de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.OPERATIONAL_CLASS;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The declared mentoring fields. Each key fixes the field's privacy class and Java value type.
 */
public enum MentoringLogKey {
    // Use the names of the shared log context without a module dependency on observability.
    TRACE_ID("traceId", CORRELATION_HANDLE, null, Pattern.compile("[0-9a-f]{32}")),
    SPAN_ID("spanId", CORRELATION_HANDLE, null, Pattern.compile("[0-9a-f]{16}")),
    WORKSPACE_ID("workspace.id", CORRELATION_HANDLE, Long.class, Pattern.compile("[0-9]{1,19}")),
    RUN_ID("run.id", CORRELATION_HANDLE, UUID.class, Pattern.compile("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")),
    RUN_EPOCH("run.epoch", OPERATIONAL_CLASS, Integer.class, Pattern.compile("[0-9]{1,10}")),
    DURATION("mentoring.duration_ms", OPERATIONAL_CLASS, Duration.class, null),
    KIND("kind", OPERATIONAL_CLASS, MentoringLogEvent.class, null);

    private final String key;
    private final MentoringFieldClass fieldClass;
    private final @Nullable Class<?> type;
    private final @Nullable Pattern contextFormat;

    MentoringLogKey(
            String key, MentoringFieldClass fieldClass, @Nullable Class<?> type, @Nullable Pattern contextFormat) {
        this.key = key;
        this.fieldClass = fieldClass;
        this.type = type;
        this.contextFormat = contextFormat;
    }

    /** The written field name. */
    public String key() {
        return key;
    }

    /** The explicit value type. A context-only field has no explicit value type. */
    public @Nullable Class<?> type() {
        return type;
    }

    MentoringFieldClass fieldClass() {
        return fieldClass;
    }

    boolean acceptsContextValue(String value) {
        if (contextFormat == null || !contextFormat.matcher(value).matches()) return false;
        fieldClass.requireAllowed(MentoringFieldClass.Sink.LOG);
        return true;
    }
}
