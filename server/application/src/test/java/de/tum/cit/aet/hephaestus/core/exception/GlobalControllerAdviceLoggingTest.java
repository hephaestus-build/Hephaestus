package de.tum.cit.aet.hephaestus.core.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;

@Tag("unit")
class GlobalControllerAdviceLoggingTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalControllerAdvice.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final GlobalControllerAdvice advice = new GlobalControllerAdvice();

    @BeforeEach
    void captureEvents() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseEvents() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"api.github.com", "gitlab.example.test", "slack.com", "outline.example.test"})
    void shouldKeepUpstreamFailuresUsefulWithoutLoggingRequestSecrets(String host) {
        URI uri = URI.create("https://private-user:private-password@" + host
                + "/private-document?access_token=private-token&X-Amz-Signature=private-signature#private-fragment");
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("private-header");
        WebClientRequestException exception = new WebClientRequestException(
                new UnknownHostException("private-error including " + uri), HttpMethod.POST, uri, headers);

        var response = advice.handleWebClientRequestException(exception);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getDetail())
                .isEqualTo("An upstream service is temporarily unavailable. Please try again later.");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(event))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "event.name",
                        "integration.request.failed",
                        "http.request.method",
                        "POST",
                        "server.address",
                        host,
                        "error.type",
                        UnknownHostException.class.getName()));
        assertThat(event.getFormattedMessage()).isEqualTo("External service request failed");
        assertThat(event.getArgumentArray()).isNullOrEmpty();
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void shouldNotLogDatabaseStatementsOrRejectedRowValues() {
        var response = advice.handleDataIntegrityViolation(new DataIntegrityViolationException(
                "private-statement", new SQLException("private-email@example.test private-row-value", "23505")));

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getDetail()).isEqualTo("The request conflicts with the current resource state.");
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(fields(event))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "event.name",
                        "database.constraint.conflict",
                        "error.type",
                        SQLException.class.getName(),
                        "db.response.status_code",
                        "23505"));
        assertThat(event.getFormattedMessage()).isEqualTo("Request conflicts with database constraints");
        assertThat(event.getArgumentArray()).isNullOrEmpty();
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void shouldOmitMalformedDatabaseStatusInsteadOfLoggingArbitraryText() {
        advice.handleDataIntegrityViolation(new DataIntegrityViolationException(
                "private-statement", new SQLException("private-row", "private-status")));
        assertThat(fields(appender.list.getFirst())).doesNotContainKey("db.response.status_code");
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        assertThat(event.getKeyValuePairs()).isNotNull();
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }
}
