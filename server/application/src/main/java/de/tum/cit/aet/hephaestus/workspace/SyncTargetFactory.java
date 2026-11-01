package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Factory for creating SyncTarget instances from workspace and repository monitor entities.
 *
 * <p>Integration metadata (installation id, PAT, auth mode) comes from the {@link ConnectionService}:
 * each call resolves the current Connection state within the caller's transaction, so there is no
 * static cache that could go stale across a credential rotation.
 */
public final class SyncTargetFactory {

    private SyncTargetFactory() {
        // Utility class - prevent instantiation
    }

    /**
     * Converts a RepositoryToMonitor to a SyncTarget for the sync service.
     *
     * @param workspace the workspace containing the repository monitor
     * @param rtm the repository to monitor entity
     * @param connectionService authoritative source for the workspace's active Connection
     * @return the SyncTarget, or empty when the workspace has no active SCM connection to authenticate with
     */
    public static Optional<SyncTarget> create(
            Workspace workspace, RepositoryToMonitor rtm, ConnectionService connectionService) {
        long workspaceId = workspace.getId();
        var gitHubApp = connectionService.findActiveGitHubAppConfig(workspaceId);
        var gitHubPat = connectionService.findActiveGitHubPatConfig(workspaceId);
        var gitLab = connectionService.findActiveGitLabConfig(workspaceId);

        AuthMode authMode;
        Long installationId = null;
        String personalAccessToken = null;

        if (gitHubApp.isPresent()) {
            authMode = AuthMode.INSTALLATION_APP;
            installationId = gitHubApp.get().installationId();
        } else if (gitHubPat.isPresent()) {
            authMode = AuthMode.PERSONAL_ACCESS_TOKEN;
            personalAccessToken = resolveBearerToken(connectionService, workspaceId, IntegrationKind.GITHUB);
        } else if (gitLab.isPresent()) {
            authMode = AuthMode.PERSONAL_ACCESS_TOKEN;
            personalAccessToken = resolveBearerToken(connectionService, workspaceId, IntegrationKind.GITLAB);
        } else {
            return Optional.empty();
        }

        return Optional.of(new SyncTarget(
                rtm.getId(),
                workspaceId,
                installationId,
                personalAccessToken,
                authMode,
                rtm.getNameWithOwner(),
                rtm.getLabelsSyncedAt(),
                rtm.getMilestonesSyncedAt(),
                rtm.getIssuesSyncedAt(),
                rtm.getPullRequestsSyncedAt(),
                rtm.getCollaboratorsSyncedAt(),
                rtm.getRepositorySyncedAt(),
                rtm.getIssueBackfillHighWaterMark(),
                rtm.getIssueBackfillCheckpoint(),
                rtm.getPullRequestBackfillHighWaterMark(),
                rtm.getPullRequestBackfillCheckpoint(),
                rtm.getBackfillLastRunAt(),
                rtm.getIssueSyncCursor(),
                rtm.getPullRequestSyncCursor(),
                rtm.getNativeId()));
    }

    private static @Nullable String resolveBearerToken(
            ConnectionService connectionService, long workspaceId, IntegrationKind kind) {
        Optional<BearerToken> bundle = connectionService.findActiveBearerToken(workspaceId, kind);
        return bundle.map(BearerToken::token).orElse(null);
    }
}
