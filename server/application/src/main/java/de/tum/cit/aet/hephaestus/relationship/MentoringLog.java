package de.tum.cit.aet.hephaestus.relationship;

import de.tum.cit.aet.hephaestus.relationship.MentoringFieldClass.Sink;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The one logger of the mentoring modules. A record has an event from a closed list ({@link MentoringLogEvent}) and
 * fields of the allowed classes only ({@link MentoringLogField}). A failure is recorded by its class, never by its
 * message or stack trace. Only validated correlation fields from the caller stay in the record.
 */
public final class MentoringLog {

    private final Logger log;

    private MentoringLog(Logger log) {
        this.log = log;
    }

    public static MentoringLog of(Class<?> owner) {
        return new MentoringLog(LoggerFactory.getLogger(owner));
    }

    public void debug(MentoringLogEvent event, MentoringLogField... fields) {
        write(log.atDebug(), event.eventName(), event.message(), fields);
    }

    public void info(MentoringLogEvent event, MentoringLogField... fields) {
        write(log.atInfo(), event.eventName(), event.message(), fields);
    }

    public void warn(MentoringLogEvent event, MentoringLogField... fields) {
        write(log.atWarn(), event.eventName(), event.message(), fields);
    }

    /** A failure: its class is the field {@code error.type}, and neither its message nor its trace is recorded. */
    public void error(MentoringLogEvent event, Throwable failure, MentoringLogField... fields) {
        write(
                log.atError().addKeyValue("error.type", failure.getClass().getName()),
                event.eventName(),
                event.message(),
                fields);
    }

    private static void write(
            LoggingEventBuilder record, String eventName, String message, MentoringLogField... fields) {
        LoggingEventBuilder event = record.addKeyValue("event.name", eventName);
        for (MentoringLogField field : fields) {
            event = event.addKeyValue(field.key(), field.valueFor(Sink.LOG));
        }
        var previous = MDC.getCopyOfContextMap();
        try {
            MDC.setContextMap(logContext(previous));
            event.log(message);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    private static Map<String, String> logContext(@Nullable Map<String, String> previous) {
        if (previous == null || previous.isEmpty()) return Map.of();

        var retained = new HashMap<String, String>();
        for (MentoringLogKey key : MentoringLogKey.values()) {
            var value = previous.get(key.key());
            if (value != null && key.acceptsContextValue(value)) retained.put(key.key(), value);
        }
        return retained;
    }
}
