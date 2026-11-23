package de.tum.cit.aet.hephaestus.relationship;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * The list of mentoring events starts empty, so this test drives the logger's private write by reflection. The first
 * producer that adds an event tests its records through the public methods.
 */
@Tag("unit")
class MentoringLogTest {

    @Test
    void shouldWriteTheEventAndOnlyTheTypedFields() throws Exception {
        UUID run = UUID.randomUUID();
        var logger = (Logger) LoggerFactory.getLogger(MentoringLogTest.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            write(
                    logger.atInfo(),
                    "mentoring.test.turn_ended",
                    "A test turn ended",
                    MentoringLogField.of(MentoringLogKey.RUN_ID, run),
                    MentoringLogField.of(MentoringLogKey.RUN_EPOCH, 3),
                    MentoringLogField.of(MentoringLogKey.DURATION, Duration.ofSeconds(2)));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("A test turn ended");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .containsExactly(
                                "event.name=mentoring.test.turn_ended",
                                "run.id=" + run,
                                "run.epoch=3",
                                "mentoring.duration_ms=2000");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void shouldRefuseAValueOfAnotherClassThanItsKeyTakes() {
        assertThatThrownBy(() -> MentoringLogField.of(MentoringLogKey.RUN_ID, "the person wrote this"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MentoringLogField.of(MentoringLogKey.RUN_ID, 42L))
                .as("an account id is no run id")
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void write(LoggingEventBuilder record, String eventName, String message, MentoringLogField... fields)
            throws Exception {
        Method write = MentoringLog.class.getDeclaredMethod(
                "write", LoggingEventBuilder.class, String.class, String.class, MentoringLogField[].class);
        write.setAccessible(true);
        write.invoke(null, record, eventName, message, fields);
    }
}
