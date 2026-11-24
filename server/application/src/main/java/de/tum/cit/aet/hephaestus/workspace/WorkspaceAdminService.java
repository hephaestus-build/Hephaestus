package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.ShownCount;
import de.tum.cit.aet.hephaestus.core.SmallCounts;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AdminWorkspaceViewDTO;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cross-tenant metadata reads require {@link WorkspaceAgnostic}; {@link WorkspaceAdminController} enforces app_admin authority. */
@Service
@WorkspaceAgnostic("Instance-admin overview lists every workspace across tenants (metadata only)")
public class WorkspaceAdminService {

    private final WorkspaceRepository workspaceRepository;
    private final MembershipRepository membershipRepository;
    private final ConnectionService connectionService;
    private final AccountDirectory accounts;

    public WorkspaceAdminService(
            WorkspaceRepository workspaceRepository,
            MembershipRepository membershipRepository,
            ConnectionService connectionService,
            AccountDirectory accounts) {
        this.workspaceRepository = workspaceRepository;
        this.membershipRepository = membershipRepository;
        this.connectionService = connectionService;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public List<AdminWorkspaceViewDTO> listAll() {
        return workspaceRepository.findAllByKind(WorkspaceKind.TEAM).stream()
                .map(this::toView)
                .toList();
    }

    /**
     * The members the workspace's member list shows, from 5. An instance admin cannot open that list in a workspace
     * that is not active, so a small count there would name nobody yet tell about few people.
     */
    private @Nullable ShownCount memberCount(Workspace ws) {
        return SmallCounts.count(Math.toIntExact(membershipRepository.countViewableMembers(ws.getId())));
    }

    private AdminWorkspaceViewDTO toView(Workspace ws) {
        IdentityProviderType providerType = connectionService
                .findActiveProviderKind(ws.getId())
                .map(IdentityProviderType::from)
                .orElse(null);
        var owner = membershipRepository.findAccountIdsByWorkspaceIdAndRole(ws.getId(), WorkspaceRole.OWNER).stream()
                .findFirst()
                .flatMap(accounts::find)
                .orElse(null);
        return new AdminWorkspaceViewDTO(
                ws.getId(),
                ws.getWorkspaceSlug(),
                ws.getDisplayName(),
                ws.getStatus(),
                ws.getAccountLogin(),
                providerType,
                owner != null ? owner.displayName() : null,
                memberCount(ws),
                ws.getCreatedAt());
    }
}
