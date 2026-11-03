package de.tum.cit.aet.hephaestus.integration.slack.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobConflictException;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobHandle;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Tag("unit")
class SlackDataSyncSchedulerTest extends BaseUnitTest {

    private static final long WS = 42L;
    private static final long CONNECTION_ID = 7L;
    private static final long OTHER_WS = 43L;
    private static final long OTHER_CONNECTION_ID = 8L;

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackChannelMetadataRefresher metadataRefresher;

    @Mock
    private SlackChannelHistorySyncService historySyncService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SyncJobService syncJobService;

    private SlackDataSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SlackDataSyncScheduler(
                monitoredChannelRepository,
                metadataRefresher,
                historySyncService,
                connectionService,
                syncJobService,
                new SlackSyncProperties("0 0 4 * * *", 10, 15, Duration.ZERO, true, 5, true, 10),
                inlineExecutor());
    }

    /**
     * Runs submitted tasks on the calling thread. The activation kick's async hop exists to keep the admin's
     * PATCH off Slack round trips, not as behaviour under test — running it inline makes the assertions
     * deterministic without a latch.
     */
    private static AsyncTaskExecutor inlineExecutor() {
        return new SimpleAsyncTaskExecutor() {
            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
    }

    @Test
    void workspaceWithoutAnActiveSlackConnection_spendsNoBudgetAndTouchesNothing() {
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.empty());

        var summary = scheduler.syncWorkspaceNow(WS);

        assertThat(summary.requestsUsed()).isZero();
        assertThat(summary.channels()).isZero();
        verify(metadataRefresher, never()).refreshWorkspace(anyLong(), any(BooleanSupplier.class));
        verify(historySyncService, never()).syncWorkspace(anyLong(), any());
    }

    @Test
    void connectedWorkspace_refreshesMetadataThenReconcilesHistory() {
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(mock(Connection.class)));
        when(historySyncService.syncWorkspace(eq(WS), any(BooleanSupplier.class)))
                .thenReturn(new SlackChannelHistorySyncService.WorkspaceSyncSummary(2, 2, 0, 5L, 3, false, 0));

        var summary = scheduler.syncWorkspaceNow(WS);

        verify(metadataRefresher).refreshWorkspace(eq(WS), any(BooleanSupplier.class));
        verify(historySyncService).syncWorkspace(eq(WS), any(BooleanSupplier.class));
        assertThat(summary.ingested()).isEqualTo(5L);
    }

    @Test
    void cron_isolatesAFailingWorkspaceFromTheRest() {
        when(monitoredChannelRepository.findDistinctWorkspaceIdsByConsentState("ACTIVE"))
                .thenReturn(List.of(1L, 2L));
        when(connectionService.findActive(1L, IntegrationKind.SLACK)).thenThrow(new IllegalStateException("boom"));
        when(connectionService.findActive(2L, IntegrationKind.SLACK)).thenReturn(Optional.empty());

        scheduler.syncDataCron();

        verify(connectionService).findActive(2L, IntegrationKind.SLACK);
    }

    @Test
    void cron_workspaceWithNoActiveConnection_recordsNoJobAndTouchesNothing() {
        when(monitoredChannelRepository.findDistinctWorkspaceIdsByConsentState("ACTIVE"))
                .thenReturn(List.of(WS));
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.empty());

        scheduler.syncDataCron();

        verify(syncJobService, never()).run(any(), any());
        verify(historySyncService, never()).syncWorkspace(anyLong(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void cron_connectedWorkspace_recordsAReconciliationJobWhoseBodyRunsTheHistorySync() {
        Connection connection = mock(Connection.class);
        when(connection.getId()).thenReturn(CONNECTION_ID);
        when(monitoredChannelRepository.findDistinctWorkspaceIdsByConsentState("ACTIVE"))
                .thenReturn(List.of(WS));
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(connection));
        when(historySyncService.syncWorkspace(eq(WS), any(BooleanSupplier.class)))
                .thenReturn(new SlackChannelHistorySyncService.WorkspaceSyncSummary(2, 2, 0, 4L, 2, false, 0));

        scheduler.syncDataCron();

        ArgumentCaptor<SyncJobRequest> requestCaptor = ArgumentCaptor.forClass(SyncJobRequest.class);
        ArgumentCaptor<Consumer<SyncJobHandle>> bodyCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(syncJobService).run(requestCaptor.capture(), bodyCaptor.capture());

        SyncJobRequest request = requestCaptor.getValue();
        assertThat(request.workspaceId()).isEqualTo(WS);
        assertThat(request.connectionId()).isEqualTo(CONNECTION_ID);
        assertThat(request.kind()).isEqualTo(IntegrationKind.SLACK);
        assertThat(request.type()).isEqualTo(SyncJobType.RECONCILIATION);
        assertThat(request.trigger()).isEqualTo(SyncJobTrigger.SCHEDULED);

        SyncJobHandle handle = mock(SyncJobHandle.class);
        bodyCaptor.getValue().accept(handle);

        verify(historySyncService).syncWorkspace(eq(WS), any(BooleanSupplier.class));
        verify(handle).progress(eq(2), eq(2), any());
    }

    @Test
    void cron_skipsWorkspaceWhoseConnectionAlreadyHasAnActiveSyncJob_butKeepsSyncingTheRest() {
        Connection busyConnection = mock(Connection.class);
        when(busyConnection.getId()).thenReturn(CONNECTION_ID);
        Connection freeConnection = mock(Connection.class);
        when(freeConnection.getId()).thenReturn(OTHER_CONNECTION_ID);

        // Two workspaces: the conflict must be contained to the first one. With a single workspace this
        // test would pass even if the try/catch were hoisted outside the loop, aborting the whole cron.
        when(monitoredChannelRepository.findDistinctWorkspaceIdsByConsentState("ACTIVE"))
                .thenReturn(List.of(WS, OTHER_WS));
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(busyConnection));
        when(connectionService.findActive(OTHER_WS, IntegrationKind.SLACK)).thenReturn(Optional.of(freeConnection));

        SyncJob activeJob = mock(SyncJob.class);
        when(activeJob.getConnection()).thenReturn(busyConnection);
        ArgumentCaptor<SyncJobRequest> requestCaptor = ArgumentCaptor.forClass(SyncJobRequest.class);
        doThrow(new SyncJobConflictException(activeJob))
                .when(syncJobService)
                .run(argThat(request -> request != null && request.workspaceId() == WS), any());

        assertThatCode(() -> scheduler.syncDataCron()).doesNotThrowAnyException();

        // The un-conflicted workspace still got its job — the conflict did not abort the fan-out.
        verify(syncJobService, times(2)).run(requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues())
                .extracting(SyncJobRequest::workspaceId)
                .containsExactlyInAnyOrder(WS, OTHER_WS);
    }

    /**
     * Consent activation must reach a channel-scoped history sync, not a workspace-wide one: consenting one
     * channel may not spend the workspace's whole request budget replaying every other channel that happens
     * to be behind.
     */
    @Test
    void channelActivation_kicksThatChannelsHistorySync_notTheWholeWorkspace() {
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(mock(Connection.class)));
        when(historySyncService.syncChannel(WS, "C1"))
                .thenReturn(new SlackChannelHistorySyncService.WorkspaceSyncSummary(1, 1, 0, 12L, 2, false, 0));

        scheduler.onChannelConsentActivated(new SlackChannelConsentService.SlackChannelActivatedEvent(WS, "C1"));

        verify(historySyncService).syncChannel(WS, "C1");
        verify(historySyncService, never()).syncWorkspace(anyLong(), any());
        verify(metadataRefresher, never()).refreshWorkspace(anyLong(), any(BooleanSupplier.class));
    }

    /** A disconnected workspace spends no budget on the kick — the same guard the nightly path applies. */
    @Test
    void channelActivation_withoutAnActiveConnection_touchesSlackNotAtAll() {
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.empty());

        scheduler.onChannelConsentActivated(new SlackChannelConsentService.SlackChannelActivatedEvent(WS, "C1"));

        verify(historySyncService, never()).syncChannel(anyLong(), any());
    }

    /**
     * The transition already committed when the kick runs, so a failing kick must not escape and must not be
     * retried inline — the nightly pass is the retry net.
     */
    @Test
    void channelActivation_swallowsAFailingKick() {
        when(connectionService.findActive(WS, IntegrationKind.SLACK)).thenReturn(Optional.of(mock(Connection.class)));
        when(historySyncService.syncChannel(WS, "C1")).thenThrow(new IllegalStateException("slack down"));

        assertThatCode(() -> scheduler.onChannelConsentActivated(
                        new SlackChannelConsentService.SlackChannelActivatedEvent(WS, "C1")))
                .doesNotThrowAnyException();
    }
}
