package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

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
    void shouldPersistAndClearOnlyTheSelectedWorkspaceMonitorError() {
        User owner = persistUser("resource-error-owner");
        Workspace first = createWorkspace("resource-error-a", "First", "acme", AccountType.ORG, owner);
        Workspace second = createWorkspace("resource-error-b", "Second", "acme", AccountType.ORG, owner);
        RepositoryToMonitor firstMonitor = monitorRepository.save(monitor(first));
        RepositoryToMonitor secondMonitor = monitorRepository.save(monitor(second));
        Long firstId = Objects.requireNonNull(firstMonitor.getId());
        Long secondId = Objects.requireNonNull(secondMonitor.getId());

        syncTargetProvider.updateSyncError(firstId, "Issue sync: ABORTED_ERROR");
        assertThat(monitorRepository.findById(firstId).orElseThrow().getLastSyncError())
                .isEqualTo("Issue sync: ABORTED_ERROR");
        assertThat(monitorRepository.findById(secondId).orElseThrow().getLastSyncError())
                .isNull();

        syncTargetProvider.updateSyncError(firstId, null);
        assertThat(monitorRepository.findById(firstId).orElseThrow().getLastSyncError())
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
