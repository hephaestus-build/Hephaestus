package de.tum.cit.aet.hephaestus.workspace;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the {@link WorkspaceTeamScope} of a workspace — the single source of truth for team
 * scoping across the team, settings, and workspace activity read paths.
 * <p>
 * Empty when the workspace has no synced organization (user-type workspace, or before first org
 * sync). Callers MUST fail closed on empty and treat the workspace as having no teams: teams only
 * exist after the org sync that populates the provider, so empty means "no teams", never "all teams".
 */
@Service
public class WorkspaceTeamScopeResolver {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceTeamScopeResolver(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceTeamScope> resolve(Workspace workspace) {
        if (workspace == null || workspace.getId() == null || workspace.getAccountLogin() == null) {
            return Optional.empty();
        }
        return workspaceRepository
                .findOrganizationProviderIdByWorkspaceId(workspace.getId())
                .map(providerId -> new WorkspaceTeamScope(workspace.getAccountLogin(), providerId));
    }
}
