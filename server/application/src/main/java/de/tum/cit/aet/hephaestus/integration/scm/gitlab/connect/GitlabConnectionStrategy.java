package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GitLab connection lifecycle strategy.
 *
 * <p>A GitLab connection is provisioned when its workspace is created, which checks the instance and the
 * token first, so {@link #initiate} refuses to connect one on its own and {@link #finalizeConnect} is never
 * reached.
 *
 * <p>{@link #revoke} cannot revoke the PAT itself (GitLab PATs are revocable only from the
 * user's profile page — there is no third-party revoke API), but it DOES tear down the group
 * webhook we registered on connect. It runs from the disconnect flow while the Connection is
 * still ACTIVE and the PAT still live — the only window in which GitLab will hand out a token to
 * delete the hook — mirroring {@code OutlineConnectionStrategy.revoke}. It then erases this
 * workspace's mirrored SCM data through {@link ScmWorkspaceContentEraser}. Local state transitions
 * are handled by the caller via {@code ConnectionService.disconnect()}.
 *
 * <p>Disconnect is GitLab's <b>only</b> erase trigger: unlike a GitHub App there is no vendor-side
 * uninstall signal for a PAT.
 */
@ConditionalOnServerRole
@Component
public class GitlabConnectionStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GitlabConnectionStrategy.class);

    private final GitLabWebhookService webhookService;
    private final ScmWorkspaceContentEraser contentEraser;

    public GitlabConnectionStrategy(GitLabWebhookService webhookService, ScmWorkspaceContentEraser contentEraser) {
        this.webhookService = webhookService;
        this.contentEraser = contentEraser;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        throw new IllegalArgumentException("A GitLab connection is made by creating a GitLab workspace");
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        return new ConnectFinalization.Failed(
                "GitLab has no vendor callback; its connection is made by creating a GitLab workspace");
    }

    @Override
    public void revoke(@Nullable IntegrationRef ref) {
        if (ref == null) {
            return;
        }
        log.info(
                "GitLab revoke called for workspace={} instanceKey={} (deregistering group webhook; PAT revoke is user-side)",
                ref.workspaceId(),
                ref.instanceKey());
        if (ref.connectionId() == null) {
            webhookService.deregisterActiveWebhook(ref.workspaceId());
        } else {
            webhookService.deregisterWebhookForConnection(ref.workspaceId(), ref.connectionId());
        }
        contentEraser.eraseWorkspaceScmMirror(ref.workspaceId());
    }

    @Override
    public void revokeProvider(IntegrationRef ref) {
        if (ref == null || ref.connectionId() == null) {
            throw new IllegalArgumentException("GitLab provider teardown requires a connection id");
        }
        webhookService.deregisterWebhookForConnectionStrict(ref.workspaceId(), ref.connectionId());
    }
}
