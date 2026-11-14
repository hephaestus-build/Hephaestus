package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredWorkspacePurges;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import org.springframework.stereotype.Component;

/** Erasure recovery purges a restored workspace again through the workspace lifecycle. */
@Component
class WorkspacePurgeRecoveryAdapter implements RestoredWorkspacePurges {
    private final WorkspaceLifecycleService lifecycle;

    WorkspacePurgeRecoveryAdapter(WorkspaceLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public boolean purgeAgain(long workspaceId) {
        return lifecycle.purgeAgain(workspaceId);
    }
}
