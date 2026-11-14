package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkspaceOrganizationLookup {
    private final OrganizationRepository organizations;

    public Optional<Organization> find(Workspace workspace) {
        return find(workspace.getOrganizationId());
    }

    public Optional<Organization> find(@Nullable Long organizationId) {
        return organizationId == null ? Optional.empty() : organizations.findById(organizationId);
    }
}
