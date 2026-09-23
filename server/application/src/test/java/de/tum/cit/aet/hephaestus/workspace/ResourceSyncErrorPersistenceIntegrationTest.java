package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncPass;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.workspace.adapter.WorkspaceSyncTargetProvider;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResourceSyncErrorPersistenceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    @Autowired
    private WorkspaceSyncTargetProvider syncTargetProvider;

    @Autowired
    private SlackMonitoredChannelRepository channelRepository;

    @Test
    void shouldKeepEachRepositoryPassErrorUntilThatPassRecovers() {
        User owner = persistUser("resource-error-owner");
        Workspace first = createWorkspace("resource-error-a", "First", "acme", AccountType.ORG, owner);
        Workspace second = createWorkspace("resource-error-b", "Second", "acme", AccountType.ORG, owner);
        RepositoryToMonitor firstMonitor = monitorRepository.save(monitor(first));
        RepositoryToMonitor secondMonitor = monitorRepository.save(monitor(second));
        Long firstId = Objects.requireNonNull(firstMonitor.getId());
        Long secondId = Objects.requireNonNull(secondMonitor.getId());

        syncTargetProvider.updateSyncError(firstId, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");
        syncTargetProvider.updateSyncError(firstId, SyncPass.HISTORICAL_BACKFILL, "Historical issue backfill aborted");
        assertThat(monitorRepository.findById(firstId).orElseThrow().getSyncErrorSummary())
                .isEqualTo("Issue sync: ABORTED_ERROR; Historical issue backfill aborted");
        assertThat(monitorRepository.findById(secondId).orElseThrow().getSyncErrorSummary())
                .isNull();

        syncTargetProvider.updateSyncError(firstId, SyncPass.RECENT, null);
        assertThat(monitorRepository.findById(firstId).orElseThrow().getSyncErrorSummary())
                .isEqualTo("Historical issue backfill aborted");

        syncTargetProvider.updateSyncError(firstId, SyncPass.RECENT, "Issue sync: ABORTED_ERROR");
        syncTargetProvider.updateSyncError(firstId, SyncPass.HISTORICAL_BACKFILL, null);
        assertThat(monitorRepository.findById(firstId).orElseThrow().getSyncErrorSummary())
                .isEqualTo("Issue sync: ABORTED_ERROR");

        syncTargetProvider.updateSyncError(firstId, SyncPass.RECENT, null);
        assertThat(monitorRepository.findById(firstId).orElseThrow().getSyncErrorSummary())
                .isNull();
    }

    @Test
    void shouldClearChannelErrorOnlyAfterAnActiveChannelWindowCompletes() {
        User owner = persistUser("channel-error-owner");
        Workspace workspace = createWorkspace("channel-error", "Channel", "acme", AccountType.ORG, owner);
        long workspaceId = Objects.requireNonNull(workspace.getId());
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setSlackTeamId("T1");
        channel.setSlackChannelId("C1");
        channel.setConsentState(ConsentState.ACTIVE);
        channelRepository.save(channel);

        assertThat(channelRepository.recordHistorySyncError(
                        workspaceId, "C1", "History sync failed (channel_not_found)"))
                .isEqualTo(1);
        assertThat(channelRepository
                        .findByWorkspaceIdAndSlackChannelId(workspaceId, "C1")
                        .orElseThrow()
                        .getLastSyncError())
                .isEqualTo("History sync failed (channel_not_found)");

        assertThat(channelRepository.advanceHistoryWatermark(workspaceId, "C1", "1780000000.000001", Instant.now()))
                .isEqualTo(1);
        assertThat(channelRepository
                        .findByWorkspaceIdAndSlackChannelId(workspaceId, "C1")
                        .orElseThrow()
                        .getLastSyncError())
                .isNull();

        channelRepository.recordHistorySyncError(workspaceId, "C1", "History sync failed (channel_not_found)");
        channelRepository.revokeConsent(workspaceId, "C1");
        assertThat(channelRepository
                        .findByWorkspaceIdAndSlackChannelId(workspaceId, "C1")
                        .orElseThrow()
                        .getLastSyncError())
                .isNull();
        assertThat(channelRepository.recordHistorySyncError(workspaceId, "C1", "stale error"))
                .isZero();
        assertThat(channelRepository.advanceHistoryWatermark(workspaceId, "C1", "1780000001.000001", Instant.now()))
                .isZero();
    }

    private static RepositoryToMonitor monitor(Workspace workspace) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner("acme/shared-repo");
        return monitor;
    }
}
