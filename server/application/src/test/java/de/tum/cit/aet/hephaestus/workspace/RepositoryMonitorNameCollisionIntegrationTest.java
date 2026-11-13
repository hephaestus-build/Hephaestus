package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RepositoryMonitorNameCollisionIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private GitLabRepositoryMonitors gitlab;

    @Test
    void shouldRenameOtherMonitorsWhenAGitHubNameIsAlreadyHeld() {
        var workspace =
                createWorkspace("rename-collision", "Rename collision", "old", AccountType.ORG, persistUser("owner"));
        workspace.resume();
        workspaces.saveAndFlush(workspace);
        long installation = workspace.getId() + 10000;
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installation),
                new ConnectionConfig.GitHubAppConfig(installation, "old", null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var original = monitor(workspace, "old/shared", 1);
        var target = monitor(workspace, "new/shared", 2);
        var other = monitor(workspace, "old/other", 3);
        var checkpoint = Instant.parse("2025-01-03T10:11:12Z");
        original.setRepositorySyncedAt(checkpoint);
        monitors.saveAndFlush(original);

        githubLifecycleListener.handleAccountRename(installation, "old", "new");

        var preserved = monitors.findById(original.getId()).orElseThrow();
        assertThat(preserved.getNameWithOwner()).isEqualTo("old/shared");
        assertThat(preserved.getNativeId()).isEqualTo(1);
        assertThat(preserved.getRepositorySyncedAt()).isEqualTo(checkpoint);
        assertThat(monitors.findById(target.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo("new/shared");
        assertThat(monitors.findById(other.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo("new/other");
    }

    @Test
    void shouldAddOtherMonitorsWhenAGitLabNameIsAlreadyHeld() {
        var workspace = createWorkspace(
                "gitlab-name-collision", "Name collision", "group", AccountType.ORG, persistUser("owner"));
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspaces.saveAndFlush(workspace);
        var connection = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "group",
                new ConnectionConfig.GitLabConfig("https://gitlab.com", 42L, null, Set.of(), null));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        var stale = monitor(workspace, "group/shared", 100);
        var replacement = repository("group/shared", 101);
        var other = repository("group/other", 102);

        assertThat(gitlab.monitorAll(workspace, List.of(replacement, other))).isEqualTo(1);

        assertThat(monitors.findByWorkspaceId(workspace.getId()))
                .extracting(RepositoryToMonitor::getNativeId)
                .containsExactlyInAnyOrder(100L, 102L);
        assertThat(monitors.findById(stale.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo("group/shared");
    }

    private RepositoryToMonitor monitor(Workspace workspace, String name, long nativeId) {
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNativeId(nativeId);
        monitor.setNameWithOwner(name);
        return monitors.saveAndFlush(monitor);
    }

    private Repository repository(String name, long nativeId) {
        var repository = new Repository();
        repository.setProvider(ensureGitLabProvider());
        repository.setNativeId(nativeId);
        repository.setName(name.substring(name.indexOf('/') + 1));
        repository.setNameWithOwner(name);
        repository.setHtmlUrl("https://gitlab.com/" + name);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        return repositories.saveAndFlush(repository);
    }
}
