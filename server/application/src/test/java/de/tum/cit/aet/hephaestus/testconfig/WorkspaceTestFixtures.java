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
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
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
        ConnectionConfig.GitLabConfig cfg = new ConnectionConfig.GitLabConfig(
                serverUrl, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of(), null);
        Connection connection = new Connection(saved, IntegrationKind.GITLAB, serverUrl, cfg);
        connection.setDisplayName(saved.getAccountLogin());
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connectionRepository.save(connection);
        return saved;
    }

    /**
     * Creates an unsaved repository monitor.
     */
    public static RepositoryToMonitor repositoryMonitor(Workspace workspace, String nameWithOwner) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(nameWithOwner);
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
            workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
            workspace.setPublicActivityEnabled(false);
        }

        public WorkspaceBuilder withStatus(Workspace.WorkspaceStatus status) {
            workspace.setStatus(status);
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
            workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
            workspace.setPublicActivityEnabled(false);
        }

        public GitLabWorkspaceBuilder withSlug(String slug) {
            workspace.setWorkspaceSlug(slug);
            return this;
        }

        public GitLabWorkspaceBuilder withStatus(Workspace.WorkspaceStatus status) {
            workspace.setStatus(status);
            return this;
        }

        public Workspace build() {
            return workspace;
        }
    }
}
