package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.McpWorkspaceDirectory;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class McpWorkspaceDirectoryAdapter implements McpWorkspaceDirectory {

    private final WorkspaceRepository workspaces;
    private final MembershipRepository memberships;

    @Override
    @Transactional(readOnly = true)
    public Optional<McpWorkspace> findActiveBySlug(String slug) {
        return workspaces
                .findByWorkspaceSlug(slug)
                .filter(workspace -> workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE)
                .map(workspace -> new McpWorkspace(
                        workspace.getId(),
                        workspace.getWorkspaceSlug(),
                        workspace.getDisplayName(),
                        workspace.isAdminPasskeyRequired()));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasMembership(long accountId, long workspaceId) {
        return memberships.existsByWorkspace_IdAndId_AccountIdAndLeftAtIsNull(workspaceId, accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAdminMembership(long accountId, long workspaceId) {
        return memberships.isAdministrator(workspaceId, accountId);
    }
}
