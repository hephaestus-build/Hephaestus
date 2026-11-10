package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

@Tag("unit")
class RuntimeRoleStartupLoggerTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldReportTheServerFlagAndWarnWhenItIsDisabled(boolean server) {
        MockEnvironment environment =
                new MockEnvironment().withProperty(RuntimeRole.SERVER_PROPERTY, Boolean.toString(server));
        Logger logger = (Logger) LoggerFactory.getLogger(RuntimeRoleStartupLogger.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new RuntimeRoleStartupLogger(environment).logRoles();

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(server ? Level.INFO : Level.WARN);
            var fields =
                    event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of("event.name", "runtime.roles.configured", "runtime.server.enabled", server));
            if (!server) {
                assertThat(event.getFormattedMessage())
                        .contains("The server role is disabled", RuntimeRole.SERVER_PROPERTY);
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
