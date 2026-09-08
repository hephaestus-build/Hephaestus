package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instance-admin cross-tenant workspace listing (metadata only). The whole service is
 * {@link WorkspaceAgnostic} so the listing — including the per-workspace connection / membership
 * lookups — runs under a tenancy bypass and sees every workspace, not just the caller's. Access is
 * gated upstream by {@code hasAuthority('app_admin')} on {@link WorkspaceAdminController}.
 */
@Service
@WorkspaceAgnostic("Instance-admin overview lists every workspace across tenants (metadata only)")
public class WorkspaceAdminService {

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceAccountMembershipRepository membershipRepository;
    private final ConnectionService connectionService;
    private final AccountIdentityQuery accountIdentityQuery;

    public WorkspaceAdminService(
            WorkspaceRepository workspaceRepository,
            WorkspaceAccountMembershipRepository membershipRepository,
            ConnectionService connectionService,
            AccountIdentityQuery accountIdentityQuery) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.connectionService = connectionService;
        this.accountIdentityQuery = accountIdentityQuery;
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceViewDTO> listAll() {
        return workspaceRepository.findAll().stream().map(this::toView).toList();
    }

    private AdminWorkspaceViewDTO toView(Workspace ws) {
        IdentityProviderType providerType = connectionService
                .findActiveProviderKind(ws.getId())
                .map(IdentityProviderType::from)
                .orElse(null);
        var owner = membershipRepository.findByWorkspace_Id(ws.getId()).stream()
                .filter(member -> !member.isSuspended() && member.getRole() == WorkspaceRole.OWNER)
                .findFirst()
                .orElse(null);
        Long ownerAccountId = owner == null ? null : owner.getAccountId();
        String ownerName = ownerAccountId == null
                ? null
                : accountIdentityQuery
                        .account(ownerAccountId)
                        .map(AccountIdentityQuery.AccountView::displayName)
                        .orElse(null);
        return new AdminWorkspaceViewDTO(
                ws.getId(),
                ws.getWorkspaceSlug(),
                ws.getDisplayName(),
                ws.getStatus().name(),
                ws.getAccountLogin(),
                providerType,
                ownerName,
                ownerAccountId,
                membershipRepository.findByWorkspace_Id(ws.getId()).stream()
                        .filter(member -> !member.isSuspended())
                        .count(),
                ws.getCreatedAt());
    }
}
