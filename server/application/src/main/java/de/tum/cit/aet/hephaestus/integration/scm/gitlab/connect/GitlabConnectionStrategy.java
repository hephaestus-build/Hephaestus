package de.tum.cit.aet.hephaestus.integration.scm.gitlab.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWebhookService;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * GitLab connection lifecycle strategy.
 *
 * <p>A GitLab connection is provisioned when its workspace is created, which checks the instance and the
 * token first, so {@link #initiate} refuses to connect one on its own and {@link #finalizeConnect} is never
 * reached.
 *
 * <p>{@link #prepareProviderTeardown} cannot revoke the PAT itself (GitLab PATs are revocable only from the
 * user's profile page — there is no third-party revoke API), but it DOES tear down the group
 * webhook we registered on connect, using the PAT it reads before the disconnect clears it.
 *
 * <p>Disconnect is GitLab's <b>only</b> erase trigger: unlike a GitHub App there is no vendor-side
 * uninstall signal for a PAT.
 */
@ConditionalOnServerRole
@Component
public class GitlabConnectionStrategy implements ConnectionStrategy {

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
    public void eraseLocalData(IntegrationRef ref) {
        contentEraser.eraseWorkspaceScmMirror(ref.workspaceId());
    }

    @Override
    public Optional<Runnable> prepareProviderTeardown(IntegrationRef ref) {
        if (ref.connectionId() == null) {
            throw new IllegalArgumentException("GitLab provider teardown requires a connection id");
        }
        return webhookService.prepareWebhookDeregistration(ref.workspaceId(), ref.connectionId());
    }
}
