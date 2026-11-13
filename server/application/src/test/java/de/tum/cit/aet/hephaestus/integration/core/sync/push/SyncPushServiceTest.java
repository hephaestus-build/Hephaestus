package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.ObjectMapper;

class SyncPushServiceTest extends BaseUnitTest {
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Mock
    private SyncEventHub hub;

    @Mock
    private SyncJobRepository syncJobs;

    private SyncPushService service() {
        return new SyncPushService(hub, new ObjectMapper(), new DataSourceProperties(), syncJobs, meters);
    }

    @Test
    void shouldPublishOnlyRoutingIdentifiersAndScopeWithoutDeliveringLocally() throws Exception {
        service()
                .onSyncStateChanged(
                        new SyncStateChangedEvent(42, 7, IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.JOB));
        var payload = ArgumentCaptor.forClass(String.class);
        verify(syncJobs).notifySyncStatus(eq(SyncPushService.CHANNEL), payload.capture());
        assertThat(new ObjectMapper().readTree(payload.getValue()))
                .isEqualTo(new ObjectMapper().readTree("{\"workspaceId\":42,\"connectionId\":7,\"scope\":\"JOB\"}"));
        assertThat(payload.getValue()).hasSizeLessThan(128);
        verifyNoInteractions(hub);
    }

    static Stream<RuntimeException> publicationFailures() {
        return Stream.of(
                new DataAccessResourceFailureException("Database disconnected"),
                new TransactionSystemException("Commit failed"));
    }

    @ParameterizedTest
    @MethodSource("publicationFailures")
    void shouldNotDeliverLocallyWhenThePublishMayHaveSucceededBeforeFailing(RuntimeException failed) {
        when(syncJobs.notifySyncStatus(anyString(), anyString())).thenThrow(failed);
        service()
                .onSyncStateChanged(
                        new SyncStateChangedEvent(42, 7, IntegrationKind.GITHUB, SyncStateChangedEvent.Scope.JOB));
        verifyNoInteractions(hub);
        assertThat(counter("postgres_publish", "failure")).isEqualTo(1);
    }

    @Test
    void shouldDeliverAnInboundNotificationToOnlyItsWorkspace() {
        service().onNotification("{\"workspaceId\":42,\"connectionId\":7,\"scope\":\"JOB\"}");
        verify(hub).publish(42, new SyncEventHint("job", 7L));
        assertThat(counter("postgres_receive", "success")).isEqualTo(1);
        verifyNoInteractions(syncJobs);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "{}",
                "invalid",
                "{\"workspaceId\":42,\"connectionId\":7}",
                "{\"workspaceId\":-1,\"connectionId\":7,\"scope\":\"JOB\"}",
                "{\"workspaceId\":42,\"connectionId\":0,\"scope\":\"JOB\"}",
                "{\"workspaceId\":42,\"connectionId\":7,\"scope\":\"UNKNOWN\"}"
            })
    void shouldIgnoreMalformedOrInvalidRoutingHints(String payload) {
        service().onNotification(payload);
        verifyNoInteractions(hub);
        assertThat(counter("postgres_receive", "failure")).isEqualTo(1);
    }

    @Test
    void shouldRefuseAnOversizedNotificationWithoutParsingItsContent() {
        service().onNotification("x".repeat(129));
        verifyNoInteractions(hub);
        assertThat(counter("postgres_receive", "failure")).isEqualTo(1);
    }

    @Test
    void shouldPublishConnectionLifecycleInvalidationsThroughTheDatabase() {
        service().onConnectionActivated(new ConnectionLifecycleEvent.Activated(7, 42, IntegrationKind.GITHUB));
        verify(syncJobs)
                .notifySyncStatus(
                        eq(SyncPushService.CHANNEL),
                        eq("{\"workspaceId\":42,\"connectionId\":7,\"scope\":\"CONNECTION\"}"));
        verifyNoInteractions(hub);
    }

    private double counter(String transport, String outcome) {
        return meters.get("integration.sync.push.messages")
                .tags("transport", transport, "outcome", outcome)
                .counter()
                .count();
    }
}
