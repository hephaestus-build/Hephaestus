package de.tum.cit.aet.hephaestus.workspace;

import java.time.Instant;

public record WorkspaceListData(
        Long id,
        String workspaceSlug,
        String displayName,
        Workspace.WorkspaceStatus status,
        String accountLogin,
        Instant createdAt,
        Boolean practicesEnabled,
        Boolean publicActivityEnabled) {}
