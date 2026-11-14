package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceStatusFixture;
import java.util.Set;
import org.springframework.test.util.ReflectionTestUtils;

/** Shared test fixtures for workspace + matching Connection rows. */
public final class WorkspaceTestFixtures {

    private WorkspaceTestFixtures() {}

    /** Unsaved active workspace with conventional defaults. */
    public static Workspace activeWorkspace(String slug) {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName("Workspace " + slug);
        workspace.setAccountLogin(slug + "-org");
        workspace.setAccountType(AccountType.ORG);
        workspace.setPublicActivityEnabled(true);
        workspace.resume();
        return workspace;
    }

    /**
     * Creates an unsaved GitHub App installation workspace with standard defaults.
     * Provider classification is supplied by the matching Connection row — call
     * {@link #persistInstallationWorkspace} to persist both atomically.
     */
    public static WorkspaceBuilder installationWorkspace(long installationId, String login) {
        return new WorkspaceBuilder(installationId, login);
    }

    /**
     * Persist a GitHub App installation workspace AND its backing {@code Connection} row.
     *
     * <p>Returns the saved Workspace; the matching ACTIVE GitHub Connection is keyed
     * by {@code installationId} and discoverable via {@code ConnectionRepository.findByWorkspaceIdAndKind}.
     */
    public static Workspace persistInstallationWorkspace(
            WorkspaceRepository workspaceRepository,
            ConnectionRepository connectionRepository,
            WorkspaceBuilder builder,
            long installationId) {
        Workspace saved = workspaceRepository.save(builder.build());
        ConnectionConfig.GitHubAppConfig cfg = new ConnectionConfig.GitHubAppConfig(
                installationId, saved.getAccountLogin(), /* serverUrl */ null, Set.of());
        Connection connection = new Connection(saved, IntegrationKind.GITHUB, Long.toString(installationId), cfg);
        connection.setDisplayName(saved.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
        return saved;
    }

    /**
     * Persist a GitLab PAT workspace AND its backing {@code Connection} row. PAT
     * itself is NOT persisted on the Connection (skipped in unit-test fixtures); use
     * {@code ConnectionService.rotateBearerToken} in tests that need a real token blob.
     */
    public static Workspace persistGitLabWorkspace(
            WorkspaceRepository workspaceRepository,
            ConnectionRepository connectionRepository,
            GitLabWorkspaceBuilder builder,
            String serverUrl) {
        Workspace saved = workspaceRepository.save(builder.build());
        connectGitLab(connectionRepository, saved, serverUrl);
        return saved;
    }

    /** Gives {@code workspace} an active GitLab connection to {@code serverUrl}. */
    public static void connectGitLab(ConnectionRepository connectionRepository, Workspace workspace, String serverUrl) {
        ConnectionConfig.GitLabConfig cfg = new ConnectionConfig.GitLabConfig(serverUrl, 42L, null, Set.of(), null);
        Connection connection = new Connection(workspace, IntegrationKind.GITLAB, serverUrl, cfg);
        connection.setDisplayName(workspace.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
    }

    /**
     * Creates an unsaved repository monitor.
     */
    public static RepositoryToMonitor repositoryMonitor(Workspace workspace, String nameWithOwner, long nativeId) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(nameWithOwner);
        monitor.setNativeId(nativeId);
        return monitor;
    }

    /**
     * Creates an unsaved GitLab PAT workspace with standard defaults. Provider
     * classification is supplied by the matching Connection row — call
     * {@link #persistGitLabWorkspace} to persist both atomically.
     */
    public static GitLabWorkspaceBuilder gitLabPatWorkspace(String groupPath) {
        return new GitLabWorkspaceBuilder(groupPath);
    }

    /**
     * Fluent builder for GitHub App installation test workspaces.
     */
    public static class WorkspaceBuilder {

        private final Workspace workspace;

        private WorkspaceBuilder(long installationId, String login) {
            workspace = new Workspace();
            workspace.setWorkspaceSlug("ws-install-" + installationId);
            workspace.setDisplayName("Workspace " + installationId);
            workspace.setAccountLogin(login);
            workspace.setAccountType(AccountType.ORG);
            workspace.setPublicActivityEnabled(false);
        }

        public WorkspaceBuilder withStatus(Workspace.WorkspaceStatus status) {
            WorkspaceStatusFixture.withStatus(workspace, status);
            return this;
        }

        public WorkspaceBuilder withAccountType(AccountType type) {
            workspace.setAccountType(type);
            return this;
        }

        public WorkspaceBuilder withRepositorySelection(RepositorySelection selection) {
            workspace.setRepositorySelection(selection);
            return this;
        }

        public WorkspaceBuilder withSlug(String slug) {
            workspace.setWorkspaceSlug(slug);
            return this;
        }

        public Workspace build() {
            return workspace;
        }
    }

    /**
     * Fluent builder for GitLab PAT test workspaces.
     */
    public static class GitLabWorkspaceBuilder {

        private final Workspace workspace;

        private GitLabWorkspaceBuilder(String groupPath) {
            workspace = new Workspace();
            workspace.setWorkspaceSlug("ws-gitlab-" + groupPath.replace("/", "-"));
            workspace.setDisplayName("GitLab " + groupPath);
            workspace.setAccountLogin(groupPath);
            workspace.setAccountType(AccountType.ORG);
            workspace.setPublicActivityEnabled(false);
        }

        public GitLabWorkspaceBuilder withSlug(String slug) {
            workspace.setWorkspaceSlug(slug);
            return this;
        }

        public GitLabWorkspaceBuilder withStatus(Workspace.WorkspaceStatus status) {
            WorkspaceStatusFixture.withStatus(workspace, status);
            return this;
        }

        public Workspace build() {
            return workspace;
        }
    }
}
