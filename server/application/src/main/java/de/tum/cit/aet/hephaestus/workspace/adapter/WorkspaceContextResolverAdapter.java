package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationDispatchScope;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WorkspaceContextResolverAdapter implements ScopeIdResolver {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceContextResolverAdapter(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public Optional<Long> findScopeIdByOrgLogin(String organizationLogin) {
        var dispatch = IntegrationDispatchScope.current();
        if (dispatch.isPresent()) return Optional.ofNullable(dispatch.get().workspaceId());
        return workspaceRepository
                .findByAccountLoginIgnoreCase(organizationLogin)
                .map(Workspace::getId);
    }

    @Override
    public Optional<Long> findScopeIdByRepositoryName(String repositoryNameWithOwner) {
        var dispatch = IntegrationDispatchScope.current();
        if (dispatch.isPresent()) return Optional.ofNullable(dispatch.get().workspaceId());
        return workspaceRepository
                .findByRepositoriesToMonitor_NameWithOwner(repositoryNameWithOwner)
                .map(Workspace::getId);
    }
}
