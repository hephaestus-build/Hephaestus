package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceKind;
import org.jspecify.annotations.NonNull;

/** A workspace of the signed-in person: their personal space, or a team workspace they belong to. */
public record SpaceDTO(
        @NonNull String workspaceSlug,
        @NonNull String displayName,
        @NonNull WorkspaceKind kind) {

    public static SpaceDTO of(Workspace workspace) {
        return new SpaceDTO(workspace.getWorkspaceSlug(), workspace.getDisplayName(), workspace.getKind());
    }
}
