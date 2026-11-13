package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries.OrganizationScope;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// An absent synced organization means no teams; callers must never treat an empty scope as unrestricted.
@Service
public class WorkspaceTeamScopeResolver {

    private final WorkspaceOrganizationLookup organizations;

    public WorkspaceTeamScopeResolver(WorkspaceOrganizationLookup organizations) {
        this.organizations = organizations;
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceTeamScope> resolve(Workspace workspace) {
        if (workspace == null || workspace.getId() == null || workspace.getAccountLogin() == null) {
            return Optional.empty();
        }
        return resolveScope(new OrganizationScope(workspace.getOrganizationId(), workspace.getAccountLogin()));
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceTeamScope> resolve(OrganizationScope scope) {
        return resolveScope(scope);
    }

    private Optional<WorkspaceTeamScope> resolveScope(OrganizationScope scope) {
        var login = scope.accountLogin();
        if (login == null) return Optional.empty();
        return organizations
                .find(scope.organizationId())
                .map(organization -> new WorkspaceTeamScope(
                        login, Objects.requireNonNull(organization.getProvider().getId())));
    }
}
