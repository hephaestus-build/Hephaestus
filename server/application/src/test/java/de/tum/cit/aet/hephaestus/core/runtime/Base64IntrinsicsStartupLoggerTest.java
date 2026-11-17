package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.management.VMOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("unit")
class Base64IntrinsicsStartupLoggerTest {

    @Test
    void shouldReportTheEffectiveValueAndWhereItCameFrom() {
        var option = new VMOption(Base64IntrinsicsStartupLogger.OPTION, "false", true, VMOption.Origin.ENVIRON_VAR);

        List<ILoggingEvent> events = logged(new Base64IntrinsicsStartupLogger(() -> Optional.of(option)));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.INFO);
        assertThat(fields(events.getFirst()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "event.name", "jvm.base64_intrinsics.configured",
                        "jvm.base64_intrinsics.enabled", false,
                        "jvm.option.origin", "ENVIRON_VAR"));
    }

    @Test
    void shouldWarnWhenTheJvmDoesNotReportTheOption() {
        List<ILoggingEvent> events = logged(new Base64IntrinsicsStartupLogger(Optional::empty));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(events.getFirst())).containsEntry("event.name", "jvm.base64_intrinsics.configured");
    }

    @Test
    void shouldReadTheOptionOfThisJvm() {
        List<ILoggingEvent> events = logged(new Base64IntrinsicsStartupLogger());

        assertThat(events).hasSize(1);
        assertThat(fields(events.getFirst())).containsKey("jvm.base64_intrinsics.enabled");
    }

    private static List<ILoggingEvent> logged(Base64IntrinsicsStartupLogger startupLogger) {
        Logger logger = (Logger) LoggerFactory.getLogger(Base64IntrinsicsStartupLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            startupLogger.logBase64Intrinsics();
            return List.copyOf(appender.list);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
