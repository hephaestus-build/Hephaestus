package de.tum.cit.aet.hephaestus.relationship;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The one logger of the mentoring modules. A record has an event from a closed list ({@link MentoringLogEvent}) and
 * fields of the allowed classes only ({@link MentoringLogField}). A failure is recorded by its class, never by its
 * message or stack trace. {@code MentoringLogFieldArchitectureTest} checks the direct dependencies and accesses of every
 * other mentoring production class against an allowed list of packages and a list of refused types and accesses.
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
            event = event.addKeyValue(field.key(), field.value());
        }
        event.log(message);
    }
}
