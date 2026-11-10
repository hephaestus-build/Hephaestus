package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.tum.cit.aet.hephaestus.integration.core.handler.PayloadParsingException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ProviderEvents;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
    private final ProviderEventStore store = mock(ProviderEventStore.class);
    private final IntegrationPoisonHandler handler = new IntegrationPoisonHandler(
            ProviderEventConsumerPropertiesFixture.defaults(), new SimpleMeterRegistry(), store);

    @ParameterizedTest
    @ValueSource(longs = {1, 10, 100, 10000, Long.MAX_VALUE})
    void shouldNeverDropTransientFailuresWhenDeliveryCountGrows(long deliveries) {
        ProviderEventStore.Claimed message = claimed(deliveries);
        handler.handleFailure(message, new DataAccessResourceFailureException("database unavailable"));
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(false));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 60})
    void shouldKeepRetryingWhenDatabaseOutageLastsMinutes(int minutes) {
        long elapsed = 0;
        long deliveries = 1;
        while (elapsed < Duration.ofMinutes(minutes).toMillis()) {
            ProviderEventStore.Claimed message = claimed(deliveries);
            handler.handleFailure(message, new DataAccessResourceFailureException("offline"));
            var delay = ArgumentCaptor.forClass(Duration.class);
            verify(store).retry(eq(1L), eq(1), delay.capture(), eq(false));
            clearInvocations(store);
            elapsed += delay.getValue().toMillis();
            deliveries++;
        }
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @Test
    void shouldRetryProviderOutage() {
        ProviderEventStore.Claimed message = claimed(100);
        handler.handleFailure(message, new ConnectException("provider unavailable"));
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(false));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @ParameterizedTest
    @MethodSource("connectionPoolFailures")
    void shouldRetryJdbcConnectionFailuresWithoutSqlState(SQLException failure) {
        ProviderEventStore.Claimed message = claimed(100);
        handler.handleFailure(message, failure);
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(false));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    private static Stream<SQLException> connectionPoolFailures() {
        return Stream.of(
                new SQLTransientConnectionException("pool unavailable"),
                new SQLRecoverableException("connection unavailable"));
    }

    @ParameterizedTest
    @MethodSource("unclassifiedIoFailures")
    void shouldCaptureUnclassifiedIoFailuresWhenBudgetIsExhausted(IOException failure) {
        ProviderEventStore.Claimed message = claimed(100);
        handler.handleFailure(message, failure);
        verify(store).dead(eq(1L), eq(1), anyString());
        verify(store, never()).retry(anyLong(), anyInt(), any(Duration.class), anyBoolean());
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
        ProviderEventStore.Claimed message = claimed(deliveries);
        handler.handleFailure(message, new IllegalStateException("unknown"));
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(true));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @Test
    void shouldNotSpendTheBudgetOnOperationalRetriesWhenAnUnknownFailureFollows() {
        ProviderEventStore.Claimed message = new ProviderEventStore.Claimed(
                1L, 42L, ProviderEvents.event("github.acme.repo.issues", "{}"), 50, 0, 1);
        handler.handleFailure(message, new IllegalStateException("unknown"));
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(true));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @Test
    void shouldCaptureUnknownFailureAtTheBudgetBeforeTermination() {
        ProviderEventStore.Claimed message = claimed(10);
        var failure = new IllegalStateException("unknown");
        handler.handleFailure(message, failure);
        verify(store).dead(1L, 1, IllegalStateException.class.getName());
    }

    @Test
    void shouldFindParsingFailureInTheCauseChain() {
        ProviderEventStore.Claimed message = claimed(1);
        var failure = new IllegalStateException("wrapper", new PayloadParsingException("invalid JSON"));
        handler.handleFailure(message, failure);
        verify(store).dead(eq(1L), eq(1), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 408, 429, 503})
    void shouldRetryWrappedProviderOutagesAndConfirmedRateLimits(int status) {
        var headers = new HttpHeaders();
        headers.set("X-RateLimit-Remaining", "0");
        var response =
                WebClientResponseException.create(status, "provider", headers, new byte[0], StandardCharsets.UTF_8);
        ProviderEventStore.Claimed message = claimed(100);
        handler.handleFailure(message, new IllegalStateException("wrapper", response));
        verify(store).retry(eq(1L), eq(1), any(Duration.class), eq(false));
        verify(store, never()).dead(anyLong(), anyInt(), anyString());
    }

    @Test
    void shouldBoundRetryDelayDuringStorm() {
        for (int attempt : new int[] {0, 1, 9, 100, Integer.MAX_VALUE}) {
            long upper = Math.min(300000L, 2000L << Math.min(30, attempt));
            assertThat(IntegrationPoisonHandler.exponentialBackoffMillis(attempt, 2000, 300000))
                    .isBetween(upper / 2, upper);
        }
    }

    private static ProviderEventStore.Claimed claimed(long attempt) {
        return new ProviderEventStore.Claimed(
                1L,
                42L,
                ProviderEvents.event("github.acme.repo.issues", "{}"),
                (int) Math.min(attempt, Integer.MAX_VALUE),
                // Every earlier attempt failed for an unknown reason.
                (int) Math.min(attempt - 1, Integer.MAX_VALUE),
                1);
    }
}
