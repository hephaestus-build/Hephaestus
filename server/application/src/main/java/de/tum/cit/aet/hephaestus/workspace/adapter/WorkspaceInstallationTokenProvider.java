package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveScmConnections;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Exposes the {@link InstallationTokenProvider} SPI on top of the connection registry: one read of the workspace's
 * lifecycle status and one of its active SCM connection, whose configuration and credential decide the rest.
 */
@Component
public class WorkspaceInstallationTokenProvider implements InstallationTokenProvider {

    private final WorkspaceRepository workspaceRepository;
    private final ActiveScmConnections activeScm;

    public WorkspaceInstallationTokenProvider(WorkspaceRepository workspaceRepository, ActiveScmConnections activeScm) {
        this.workspaceRepository = workspaceRepository;
        this.activeScm = activeScm;
    }

    @Override
    public ScmConnection connection(Long scopeId) {
        return activeScm
                .config(scopeId)
                .map(WorkspaceInstallationTokenProvider::settings)
                .orElse(ScmConnection.NONE);
    }

    @Override
    public ScmAccess access(Long scopeId) {
        boolean active = workspaceRepository
                .findById(scopeId)
                .map(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .orElse(false);
        if (!active) {
            // An inactive scope must not call the provider, so its connection is not read.
            return new ScmAccess(false, ScmConnection.NONE, null);
        }
        return activeScm
                .withToken(scopeId)
                .map(scm -> new ScmAccess(
                        true,
                        settings(scm.config()),
                        scm.bearer() == null ? null : scm.bearer().token()))
                .orElse(new ScmAccess(true, ScmConnection.NONE, null));
    }

    private static ScmConnection settings(ConnectionConfig config) {
        return switch (config) {
            case ConnectionConfig.GitHubAppConfig app ->
                new ScmConnection(AuthMode.INSTALLATION_APP, app.installationId(), url(app.serverUrl()));
            case ConnectionConfig.GitHubPatConfig pat ->
                new ScmConnection(AuthMode.PERSONAL_ACCESS_TOKEN, null, url(pat.serverUrl()));
            case ConnectionConfig.GitLabConfig gitlab ->
                new ScmConnection(AuthMode.PERSONAL_ACCESS_TOKEN, null, url(gitlab.serverUrl()));
            default -> ScmConnection.NONE;
        };
    }

    private static @Nullable String url(@Nullable String configured) {
        return configured == null || configured.isBlank() ? null : configured;
    }
}
