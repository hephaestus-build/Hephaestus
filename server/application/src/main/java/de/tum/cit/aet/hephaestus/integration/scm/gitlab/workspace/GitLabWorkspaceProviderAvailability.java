package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Exposes the GitLab default server URL to the workspace creation wizard when GitLab is enabled. */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
public class GitLabWorkspaceProviderAvailability implements WorkspaceProviderAvailability {

    private final GitLabProperties gitLabProperties;

    public GitLabWorkspaceProviderAvailability(GitLabProperties gitLabProperties) {
        this.gitLabProperties = gitLabProperties;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITLAB;
    }

    @Override
    public Optional<String> hintUrl() {
        return Optional.of(gitLabProperties.defaultServerUrl());
    }
}
