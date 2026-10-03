package de.tum.cit.aet.hephaestus.integration.core.sync.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

class ConnectionActivityRecorderTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 1L;
    private static final long CONNECTION_ID = 10L;
    private static final long OTHER_CONNECTION_ID = 20L;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ConnectionActivityRepository activityRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private MutableClock clock;
    private ConnectionActivityRecorder recorder;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-07-14T10:00:00Z"), ZoneId.of("UTC"));
        lenient()
                .doAnswer(invocation -> {
                    Consumer<TransactionStatus> action = invocation.getArgument(0);
                    action.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        recorder = new ConnectionActivityRecorder(
                connectionRepository, activityRepository, eventPublisher, clock, transactionTemplate);
    }

    @Test
    void recordEventProcessed_twoCallsWithinThrottleWindow_writesOnce() {
        stubActiveConnection(WORKSPACE_ID, IntegrationKind.GITHUB, CONNECTION_ID);

        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");
        clock.advance(Duration.ofSeconds(5)); // still inside the 15s throttle window
        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");

        verify(activityRepository, times(1)).upsertActivity(eq(CONNECTION_ID), eq(WORKSPACE_ID), any(), eq("push"));
        verify(eventPublisher, times(1)).publishEvent(any(SyncStateChangedEvent.class));
    }

    @Test
    void recordEventProcessed_secondCallAfterThrottleWindow_writesAgain() {
        stubActiveConnection(WORKSPACE_ID, IntegrationKind.GITHUB, CONNECTION_ID);

        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");
        clock.advance(Duration.ofSeconds(16)); // past the 15s throttle window
        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "pull_request");

        verify(activityRepository, times(2)).upsertActivity(eq(CONNECTION_ID), eq(WORKSPACE_ID), any(), any());
    }

    @Test
    void recordEventProcessed_noActiveConnection_noOp() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE))
                .thenReturn(Optional.empty());

        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");

        verifyNoInteractions(activityRepository);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void recordEventProcessed_repositoryThrows_neverPropagates() {
        stubActiveConnection(WORKSPACE_ID, IntegrationKind.GITHUB, CONNECTION_ID);
        doThrow(new RuntimeException("boom"))
                .when(activityRepository)
                .upsertActivity(anyLong(), anyLong(), any(), any());

        assertThatCode(() -> recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordEventProcessed_transactionCommitFails_neverPropagates() {
        doThrow(new UnexpectedRollbackException("commit failed"))
                .when(transactionTemplate)
                .executeWithoutResult(any());

        assertThatCode(() -> recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push"))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidate_forcesReResolutionOfConnectionIdOnNextCall() {
        stubActiveConnection(WORKSPACE_ID, IntegrationKind.GITHUB, CONNECTION_ID);
        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");
        verify(connectionRepository, times(1))
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE);

        recorder.invalidate(WORKSPACE_ID, IntegrationKind.GITHUB);
        stubActiveConnection(WORKSPACE_ID, IntegrationKind.GITHUB, OTHER_CONNECTION_ID);
        clock.advance(Duration.ofSeconds(16)); // past the throttle window so the write isn't swallowed

        recorder.recordEventProcessed(WORKSPACE_ID, IntegrationKind.GITHUB, "push");

        verify(connectionRepository, times(2))
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        WORKSPACE_ID, IntegrationKind.GITHUB, IntegrationState.ACTIVE);
        ArgumentCaptor<Long> connectionIdCaptor = ArgumentCaptor.forClass(Long.class);
        verify(activityRepository, times(2))
                .upsertActivity(connectionIdCaptor.capture(), eq(WORKSPACE_ID), any(), any());
        assertThat(connectionIdCaptor.getAllValues()).containsExactly(CONNECTION_ID, OTHER_CONNECTION_ID);
    }

    private void stubActiveConnection(long workspaceId, IntegrationKind kind, long connectionId) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        Connection connection = new Connection(
                workspace, kind, "100", new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of()));
        setConnectionId(connection, connectionId);
        connection.setState(IntegrationState.ACTIVE);
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        workspaceId, kind, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(connection));
    }

    private static void setConnectionId(Connection connection, long id) {
        try {
            Field field = Connection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(connection, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Mutable {@link Clock} test double — advances only when {@link #advance(Duration)} is called. */
    private static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
