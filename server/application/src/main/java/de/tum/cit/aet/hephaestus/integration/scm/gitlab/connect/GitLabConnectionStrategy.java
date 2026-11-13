package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.identity.GitLabWorkspaceInstance;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabPreflightService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * GitLab connection lifecycle strategy.
 *
 * <p>A GitLab connection is provisioned when its workspace is created, which checks the instance and the
 * token first. {@link #initiate} reconnects a disconnected workspace to that same group with a freshly
 * verified token; {@link #finalizeConnect} is never reached.
 *
 * <p>The durable resource repair cannot revoke the PAT itself (GitLab PATs are revocable only from the
 * user's profile page — there is no third-party revoke API), but it DOES tear down the group
 * webhook we registered on connect, using the PAT it reads before the disconnect clears it.
 *
 * <p>Disconnect is GitLab's <b>only</b> erase trigger: unlike a GitHub App there is no vendor-side
 * uninstall signal for a PAT.
 */
@ConditionalOnServerRole
@Component
public class GitLabConnectionStrategy implements ConnectionStrategy {

    private final ScmWorkspaceContentEraser contentEraser;
    private final ConnectionRepository connections;
    private final WorkspaceRepository workspaces;
    private final GitLabWorkspaceInstance instance;
    private final GitLabPreflightService preflight;

    public GitLabConnectionStrategy(
            ScmWorkspaceContentEraser contentEraser,
            ConnectionRepository connections,
            WorkspaceRepository workspaces,
            GitLabWorkspaceInstance instance,
            GitLabPreflightService preflight) {
        this.contentEraser = contentEraser;
        this.connections = connections;
        this.workspaces = workspaces;
        this.instance = instance;
        this.preflight = preflight;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        String token = request.userInput().get("personalAccessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Enter a personal access token to reconnect GitLab.");
        }
        String serverUrl = instance.require(null);
        var previous = connections
                .findByWorkspaceIdAndKindAndInstanceKey(request.workspaceId(), IntegrationKind.GITLAB, serverUrl)
                .filter(connection -> connection.getState() == IntegrationState.UNINSTALLED)
                .orElseThrow(
                        () -> new IllegalArgumentException(
                                "This workspace has no disconnected GitLab connection. Create a GitLab workspace to connect one."));
        if (!(previous.getConfig() instanceof ConnectionConfig.GitLabConfig config)) {
            throw new IllegalStateException("The disconnected GitLab connection has no group configuration.");
        }
        var workspace = workspaces
                .findById(request.workspaceId())
                .orElseThrow(() -> new IllegalArgumentException("This workspace no longer exists."));
        long groupId = preflight.resolveAccountId(token, serverUrl, workspace.getAccountLogin());
        if (groupId != config.gitlabGroupId()) {
            throw new IllegalArgumentException("The GitLab group has changed. Create a workspace for the new group.");
        }
        return new ConnectInitiation.AcceptInline(new BearerToken(token, null), serverUrl);
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        return new ConnectFinalization.Failed(
                "GitLab has no vendor callback. You make a GitLab connection when you create a GitLab workspace.");
    }

    @Override
    public void eraseLocalData(IntegrationRef ref) {
        contentEraser.eraseWorkspaceScmMirror(ref.workspaceId());
    }
}
