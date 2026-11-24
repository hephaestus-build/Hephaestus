package de.tum.cit.aet.hephaestus.relationship;

import static de.tum.cit.aet.hephaestus.relationship.MentoringLogEvent.OPERATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@Tag("unit")
class MentoringLogTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldWriteOnlyDeclaredFieldsEvenWithPersonDataInTheRequestContext(boolean validCorrelation) {
        UUID run = UUID.randomUUID();
        var logger = (Logger) LoggerFactory.getLogger(MentoringLogTest.class);
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        appender.start();
        logger.addAppender(appender);
        var previous = MDC.getCopyOfContextMap();
        var correlation = Map.of(
                "traceId", "72f59d41a0bc67081203edccf8d491fa",
                "spanId", "8d40f2c77b9ea110",
                "workspace.id", "42",
                "run.id", run.toString(),
                "run.epoch", "3");
        var requestFields = new HashMap<String, String>(correlation);
        if (!validCorrelation) correlation.keySet().forEach(key -> requestFields.put(key, "private request text"));
        requestFields.putAll(Map.of(
                "email", "private@example.invalid",
                "name", "Private Name",
                "login", "private-login",
                "message", "private request text",
                "action.id", "d28b015a-3d31-4292-bfb9-a69162d978d3",
                "action.kind", "SLACK_POST",
                "action.attempt", "1",
                "subject.key", "conversation:2b880631-c313-43a5-b066-298d86160960"));
        MDC.setContextMap(requestFields);
        try {
            MentoringLog.of(MentoringLogTest.class)
                    .error(
                            OPERATION_FAILED,
                            new IllegalStateException("a private provider response"),
                            MentoringLogField.of(MentoringLogKey.RUN_ID, run),
                            MentoringLogField.of(MentoringLogKey.RUN_EPOCH, 3),
                            MentoringLogField.of(MentoringLogKey.DURATION, Duration.ofSeconds(2)));

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("A mentoring operation failed");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).isEqualTo(validCorrelation ? correlation : Map.of());
                assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .containsExactly(
                                "error.type=java.lang.IllegalStateException",
                                "event.name=mentoring.operation.failed",
                                "run.id=" + run,
                                "run.epoch=3",
                                "mentoring.duration_ms=2000");
            });
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(requestFields);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
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
}
