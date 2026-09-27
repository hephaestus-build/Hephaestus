package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The one policy for monitoring repositories GitLab reports after a workspace is set up — a new project, a project moved
 * into the group, or one a later discovery lists: a workspace that follows every repository of its group monitors each
 * one the repository filter allows.
 */
@Component
public class GitLabRepositoryMonitors {

    private final GitLabWorkspaceInitializationService initializationService;
    private final WorkspaceScopeFilter workspaceScopeFilter;

    public GitLabRepositoryMonitors(
            GitLabWorkspaceInitializationService initializationService, WorkspaceScopeFilter workspaceScopeFilter) {
        this.initializationService = initializationService;
        this.workspaceScopeFilter = workspaceScopeFilter;
    }

    /** Whether {@code workspace} may monitor {@code nameWithOwner}, a repository inside its group, under this policy. */
    public boolean isAllowed(Workspace workspace, String nameWithOwner) {
        return workspace.getRepositorySelection() != RepositorySelection.SELECTED
                && workspaceScopeFilter.isRepositoryAllowed(workspace, nameWithOwner);
    }

    /** Monitors the allowed ones among {@code repositories}; returns how many monitors were created. */
    public int monitorAllowed(Workspace workspace, Collection<Repository> repositories) {
        List<Repository> allowed = repositories.stream()
                .filter(repository -> isAllowed(workspace, repository.getNameWithOwner()))
                .toList();
        return allowed.isEmpty() ? 0 : initializationService.ensureRepositoryMonitors(workspace, allowed);
    }
}
