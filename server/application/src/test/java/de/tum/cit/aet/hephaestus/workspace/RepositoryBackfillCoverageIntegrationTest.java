package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RepositoryBackfillCoverageIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldRepairIncompleteCoverageDespiteAnEarlierCountGapOnlyForItsCompletedWorkspaceMonitor() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("coverage-" + UUID.randomUUID()));
        var monitor = WorkspaceTestFixtures.repositoryMonitor(workspace, "example/repo", 1L);
        Instant recentSync = Instant.parse("2026-01-01T00:00:00Z");
        monitor.setIssueBackfillHighWaterMark(900);
        monitor.setIssueBackfillCheckpoint(0);
        monitor.setIssueSyncCursor("issue-cursor");
        monitor.setPullRequestBackfillHighWaterMark(950);
        monitor.setPullRequestBackfillCheckpoint(0);
        monitor.setPullRequestSyncCursor("pr-cursor");
        monitor.setBackfillRepairProviderCount(100);
        monitor.setPullRequestsSyncedAt(recentSync);
        monitors.saveAndFlush(monitor);

        assertThat(monitors.restartCompletedBackfill(workspace.getId(), monitor.getId(), 100, 99, false))
                .isZero();
        assertThat(monitors.restartCompletedBackfill(workspace.getId() + 10000, monitor.getId(), 100, 99, true))
                .isZero();
        assertThat(monitors.restartCompletedBackfill(workspace.getId(), monitor.getId(), 100, 99, true))
                .isEqualTo(1);

        var stored = jdbc.queryForMap(
                "SELECT issue_backfill_high_water_mark,issue_backfill_checkpoint,issue_sync_cursor,pull_request_backfill_high_water_mark,pull_request_backfill_checkpoint,pull_request_sync_cursor,pull_requests_synced_at FROM repository_to_monitor WHERE id=?",
                monitor.getId());
        assertThat(stored)
                .containsEntry("issue_backfill_high_water_mark", null)
                .containsEntry("issue_backfill_checkpoint", null)
                .containsEntry("issue_sync_cursor", null)
                .containsEntry("pull_request_backfill_high_water_mark", null)
                .containsEntry("pull_request_backfill_checkpoint", null)
                .containsEntry("pull_request_sync_cursor", null)
                .containsEntry("pull_requests_synced_at", Timestamp.from(recentSync));
        assertThat(monitors.restartCompletedBackfill(workspace.getId(), monitor.getId(), 100, 99, true))
                .isZero();
    }
}
