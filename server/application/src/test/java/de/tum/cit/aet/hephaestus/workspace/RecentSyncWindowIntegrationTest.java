package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncCursorKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.RecentWindow;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A recent sync window survives between runs on its sync target, apart from the backfill's cursor. */
class RecentSyncWindowIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private SyncTargetProvider syncTargets;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private CredentialBundleConverter credentials;

    @Test
    void shouldReadAnOpenWindowBackOnTheSyncTargetUntilItCloses() {
        long monitorId = monitoredRepository();
        var opened = new RecentWindow("cursor-at-cap", Instant.parse("2026-11-20T03:00:00Z"));

        syncTargets.updateRecentWindow(monitorId, SyncCursorKind.PULL_REQUEST, opened);

        var target = syncTargets.findSyncTargetById(monitorId).orElseThrow();
        assertThat(target.pullRequestRecentWindow()).isEqualTo(opened);
        assertThat(target.issueRecentWindow()).isNull();
        assertThat(target.pullRequestSyncCursor()).as("the backfill's cursor").isNull();

        syncTargets.updateRecentWindow(monitorId, SyncCursorKind.PULL_REQUEST, null);

        assertThat(syncTargets.findSyncTargetById(monitorId).orElseThrow().pullRequestRecentWindow())
                .isNull();
    }

    private long monitoredRepository() {
        String suffix = Long.toString(System.nanoTime());
        var owner = persistUser("recent-window-owner-" + suffix);
        Workspace workspace = createWorkspace(
                "recent-window-" + suffix, "Recent window", "recent-window-org-" + suffix, AccountType.ORG, owner);
        long installationId = System.nanoTime();
        var github = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, workspace.getAccountLogin(), null, Set.of()));
        github.setCredentials(new InstallationCredential(installationId, "app"), credentials);
        ConnectionFixtures.reachState(github, IntegrationState.ACTIVE);
        connections.save(github);
        var monitor = new RepositoryToMonitor();
        monitor.setNativeId(1L);
        monitor.setNameWithOwner(workspace.getAccountLogin() + "/recent-window");
        monitor.setWorkspace(workspace);
        return Objects.requireNonNull(monitors.save(monitor).getId());
    }
}
