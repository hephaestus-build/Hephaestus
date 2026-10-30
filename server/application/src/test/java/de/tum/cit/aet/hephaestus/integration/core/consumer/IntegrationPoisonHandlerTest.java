package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class IntegrationPoisonHandlerTest extends BaseUnitTest {
    private final ConsumerDeadLetterService deadLetters = mock(ConsumerDeadLetterService.class);
    private final IntegrationPoisonHandler handler = new IntegrationPoisonHandler(
            NatsConsumerPropertiesFixture.defaults(), new SimpleMeterRegistry(), deadLetters);

    @ParameterizedTest
    @ValueSource(longs = {1, 10, 100, 10000, Long.MAX_VALUE})
    void shouldNeverDropTransientFailuresWhenDeliveryCountGrows(long deliveries) {
        Message message = message(deliveries);
        handler.handleFailure(42L, message, new DataAccessResourceFailureException("database unavailable"));
        verify(message).nakWithDelay(any(Duration.class));
        verify(message, never()).ack();
        verify(message, never()).term();
        verifyNoInteractions(deadLetters);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 60})
    void shouldKeepRetryingWhenDatabaseOutageLastsMinutes(int minutes) {
        long elapsed = 0;
        long deliveries = 1;
        while (elapsed < Duration.ofMinutes(minutes).toMillis()) {
            Message message = message(deliveries);
            handler.handleFailure(42L, message, new DataAccessResourceFailureException("offline"));
            verify(message, never()).ack();
            verify(message, never()).term();
            var delay = ArgumentCaptor.forClass(Duration.class);
            verify(message).nakWithDelay(delay.capture());
            elapsed += delay.getValue().toMillis();
            deliveries++;
        }
        verifyNoInteractions(deadLetters);
    }

    @Test
    void shouldRetryProviderOutage() {
        Message message = message(100);
        handler.handleFailure(42L, message, new ConnectException("provider unavailable"));
        verify(message).nakWithDelay(any(Duration.class));
        verifyNoInteractions(deadLetters);
    }

    @ParameterizedTest
    @MethodSource("connectionPoolFailures")
    void shouldRetryJdbcConnectionFailuresWithoutSqlState(SQLException failure) {
        Message message = message(100);
        handler.handleFailure(42L, message, failure);
        verify(message).nakWithDelay(any(Duration.class));
        verify(message, never()).term();
        verifyNoInteractions(deadLetters);
    }

    private static Stream<SQLException> connectionPoolFailures() {
        return Stream.of(
                new SQLTransientConnectionException("pool unavailable"),
                new SQLRecoverableException("connection unavailable"));
    }

    @ParameterizedTest
    @MethodSource("unclassifiedIoFailures")
    void shouldCaptureUnclassifiedIoFailuresWhenBudgetIsExhausted(IOException failure) {
        Message message = message(100);
        handler.handleFailure(42L, message, failure);
        verify(deadLetters).capture(42L, message, failure);
        verify(message).term();
        verify(message, never()).nakWithDelay(any(Duration.class));
    }

    private static Stream<IOException> unclassifiedIoFailures() {
        return Stream.of(
                new IOException("unknown I/O failure"),
                new FileNotFoundException(),
                new MalformedURLException(),
                new CharacterCodingException());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 9})
    void shouldRetryUnknownFailureBeforeItsBudgetIsExhausted(long deliveries) {
        Message message = message(deliveries);
        handler.handleFailure(42L, message, new IllegalStateException("unknown"));
        verify(message).nakWithDelay(any(Duration.class));
        verifyNoInteractions(deadLetters);
    }

    @Test
    void shouldCaptureUnknownFailureAtTheBudgetBeforeTermination() {
        Message message = message(10);
        var failure = new IllegalStateException("unknown");
        handler.handleFailure(42L, message, failure);
        var order = inOrder(deadLetters, message);
        order.verify(deadLetters).capture(42L, message, failure);
        order.verify(message).term();
    }

    @Test
    void shouldFindParsingFailureInTheCauseChain() {
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn("github.acme.repo.issues");
        var failure = new IllegalStateException("wrapper", new PayloadParsingException("invalid JSON"));
        handler.handleFailure(42L, message, failure);
        verify(deadLetters).capture(42L, message, failure);
        verify(message).term();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 408, 429, 503})
    void shouldRetryWrappedProviderOutagesAndConfirmedRateLimits(int status) {
        var headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        var response =
                WebClientResponseException.create(status, "provider", headers, new byte[0], StandardCharsets.UTF_8);
        Message message = message(100);
        handler.handleFailure(42L, message, new IllegalStateException("wrapper", response));
        verify(message).nakWithDelay(any(Duration.class));
        verifyNoInteractions(deadLetters);
    }

    @Test
    void shouldCommitDeadLetterBeforeTerminatingParsingFailure() {
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn("github.acme.repo.issues");
        var failure = new PayloadParsingException("invalid JSON");
        handler.handleFailure(42L, message, failure);
        var order = inOrder(deadLetters, message);
        order.verify(deadLetters).capture(42L, message, failure);
        order.verify(message).term();
        verify(message, never()).ack();
    }

    @Test
    void shouldRetryPoisonWhenItsDurableCaptureFails() {
        Message message = message(100);
        doThrow(new DataAccessResourceFailureException("offline"))
                .when(deadLetters)
                .capture(any(), any(), any());
        handler.handleFailure(42L, message, new PayloadParsingException("invalid JSON"));
        verify(message).nakWithDelay(any(Duration.class));
        verify(message, never()).term();
        verify(message, never()).ack();
    }

    @Test
    void shouldBoundRetryDelayDuringStorm() {
        for (int attempt : new int[] {0, 1, 9, 100, Integer.MAX_VALUE}) {
            long upper = Math.min(300000L, 2000L << Math.min(30, attempt));
            assertThat(IntegrationPoisonHandler.exponentialBackoffMillis(attempt, 2000, 300000))
                    .isBetween(upper / 2, upper);
        }
    }

    private static Message message(long deliveries) {
        Message message = mock(Message.class);
        NatsJetStreamMetaData metadata = mock(NatsJetStreamMetaData.class);
        when(message.metaData()).thenReturn(metadata);
        when(metadata.deliveredCount()).thenReturn(deliveries);
        when(message.getSubject()).thenReturn("github.acme.repo.issues");
        return message;
    }
}
