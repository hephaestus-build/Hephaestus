package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * {@link ScmTokenSource} for GitLab — exposes the PAT bound to a workspace's active
 * GitLab connection plus the resolved server URL.
 *
 * <p>Wraps {@link GitLabTokenService} so cross-module callers (agent context preparation)
 * don't need to import the GitLab service directly. {@link ConditionalOnBean} gates this
 * impl on the GitLab service existing — under the webhook-only runtime role the GitLab
 * stack is disabled and this bean simply doesn't register.
 */
@Component
@ConditionalOnBean(GitLabTokenService.class)
public class GitLabScmTokenSource implements ScmTokenSource {

    private final GitLabTokenService gitLabTokenService;

    public GitLabScmTokenSource(GitLabTokenService gitLabTokenService) {
        this.gitLabTokenService = gitLabTokenService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Optional<String> accessToken(long scopeId) {
        String token = gitLabTokenService.getAccessToken(scopeId);
        return (token == null || token.isBlank()) ? Optional.empty() : Optional.of(token);
    }

    @Override
    public Optional<String> serverUrl(long scopeId) {
        String url = gitLabTokenService.resolveServerUrl(scopeId);
        return (url == null || url.isBlank()) ? Optional.empty() : Optional.of(url);
    }

    @Override
    public Optional<String> reviewHeadRef(long pullRequestNumber) {
        return pullRequestNumber > 0
                ? Optional.of("refs/merge-requests/" + pullRequestNumber + "/head")
                : Optional.empty();
    }

    @Override
    public boolean recordsReviewDiffBase() {
        return true;
    }
}
