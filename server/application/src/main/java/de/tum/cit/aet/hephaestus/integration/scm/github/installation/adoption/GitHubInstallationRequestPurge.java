package de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import org.springframework.stereotype.Component;

/** An adopted installation whose workspace is purged stays adopted, with no workspace. */
@Component
@ConditionalOnServerRole
class GitHubInstallationRequestPurge implements WorkspacePurgeContributor {
    private final GitHubInstallationRequestRepository requests;

    GitHubInstallationRequestPurge(GitHubInstallationRequestRepository requests) {
        this.requests = requests;
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        requests.clearWorkspace(workspaceId);
    }
}
