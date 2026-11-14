package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceLocks;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceRootsAdapter implements WorkspaceRoots, WorkspaceLocks {
    private final WorkspaceRepository workspaces;
    private final MembershipService memberships;

    @Override
    public Optional<Workspace> lockByInstallation(long installationId) {
        return workspaces.findByInstallationIdForUpdate(installationId);
    }

    @Override
    public Optional<Workspace> lockByOrganization(long organizationId) {
        return workspaces.findByOrganizationIdForUpdate(organizationId);
    }

    @Override
    public boolean suspend(long workspaceId) {
        var workspace = locked(workspaceId);
        if (!workspace.suspend()) return false;
        workspaces.save(workspace);
        return true;
    }

    @Override
    public boolean resume(long workspaceId) {
        var workspace = locked(workspaceId);
        if (!workspace.resume()) return false;
        workspaces.save(workspace);
        return true;
    }

    @Override
    public void selectRepositories(long workspaceId, RepositorySelection selection) {
        var workspace = locked(workspaceId);
        if (workspace.getRepositorySelection() == selection) return;
        workspace.setRepositorySelection(selection);
        workspaces.save(workspace);
    }

    @Override
    public void renameAccount(long workspaceId, String accountLogin) {
        var workspace = locked(workspaceId);
        if (accountLogin.equals(workspace.getAccountLogin())) return;
        workspace.setAccountLogin(accountLogin);
        workspaces.save(workspace);
    }

    @Override
    public void linkOrganization(long workspaceId, long organizationId) {
        var workspace = locked(workspaceId);
        if (Objects.equals(workspace.getOrganizationId(), organizationId)) return;
        workspace.setOrganizationId(organizationId);
        workspaces.save(workspace);
    }

    @Override
    public Workspace create(
            String slug,
            String displayName,
            String accountLogin,
            AccountType accountType,
            @Nullable Long ownerAccountId) {
        Workspace saved = workspaces.save(Workspace.create(slug, displayName, accountLogin, accountType));
        if (ownerAccountId != null) {
            memberships.createMembership(saved, ownerAccountId, Membership.WorkspaceRole.OWNER);
        }
        return saved;
    }

    private Workspace locked(long workspaceId) {
        return workspaces.findByIdForUpdate(workspaceId).orElseThrow();
    }
}
