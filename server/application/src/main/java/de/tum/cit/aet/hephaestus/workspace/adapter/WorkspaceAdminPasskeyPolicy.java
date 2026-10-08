package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.auth.spi.AdminPasskeyAccess;
import de.tum.cit.aet.hephaestus.core.auth.spi.WorkspaceAdminAssurance;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class WorkspaceAdminPasskeyPolicy implements WorkspaceAdminAssurance {
    private final WorkspaceRepository workspaces;
    private final AdminPasskeyAccess access;

    @Override
    public void require(boolean sensitive) {
        var context = WorkspaceContextHolder.getContext();
        if (context == null) {
            throw new IllegalStateException("Workspace context is required");
        }
        var workspace = workspaces.findById(context.id()).orElseThrow();
        access.requireWorkspaceAdmin(workspace.isAdminPasskeyRequired(), SecurityUtils.isSuperAdmin(), sensitive);
    }
}
