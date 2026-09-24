package de.tum.cit.aet.hephaestus.integration.outline.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class OutlineApiClientLoggingTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 400, 503})
    void shouldKeepProviderFailuresStructuredWithoutPrivatePayloads(int status) {
        var client = new OutlineApiClient(
                CircuitBreaker.ofDefaults("logging-contract"),
                Retry.of("logging-contract", RetryConfig.custom().maxAttempts(1).build()),
                WebClient.builder()
                        .exchangeFunction(request -> status == 0
                                ? Mono.error(new IllegalArgumentException("private-transport-message"))
                                : Mono.just(ClientResponse.create(HttpStatus.valueOf(status))
                                        .body("private-provider-response")
                                        .build()))
                        .build(),
                new OutlineOriginPolicy(Set.of("https://wiki.example.com")));
        Logger logger = (Logger) LoggerFactory.getLogger(OutlineApiClient.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertThatThrownBy(() -> client.validateToken("https://wiki.example.com", "private-api-token"))
                    .isInstanceOfSatisfying(
                            OutlineApiException.class,
                            failure -> assertThat(failure.isRetryable()).isEqualTo(status == 0 || status >= 500));
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getFormattedMessage()).isEqualTo("Outline API request failed");
            assertThat(event.getKeyValuePairs()).isNotNull();
            var fields =
                    event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields)
                    .containsEntry("event.name", "integration.request.failed")
                    .containsEntry("integration.kind", "OUTLINE")
                    .containsEntry("integration.operation", "/api/auth.info")
                    .containsEntry("server.address", "wiki.example.com");
            if (status == 0) assertThat(fields).doesNotContainKey("http.response.status_code");
            else assertThat(fields).containsEntry("http.response.status_code", status);
            assertThat(fields).containsKey("error.type");
            assertThat(fields).hasSize(status == 0 ? 5 : 6);
            assertThat(fields.toString()).doesNotContain("private-");
            assertThat(event.getArgumentArray()).isNullOrEmpty();
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
