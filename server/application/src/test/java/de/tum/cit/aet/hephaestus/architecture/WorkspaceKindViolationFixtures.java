package de.tum.cit.aet.hephaestus.architecture;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.PersonalAllowed;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import java.util.Optional;

/** What {@code WorkspaceKindArchitectureTest} must refuse. The controllers map no route, and nothing calls the reader. */
final class WorkspaceKindViolationFixtures {

    private WorkspaceKindViolationFixtures() {}

    @WorkspaceScopedController
    static class Neither {}

    @WorkspaceScopedController
    @TeamOnly
    @PersonalAllowed
    static class Both {}

    static final class AnyKindSlugReader {
        Optional<Workspace> read(WorkspaceRepository workspaces, String slug) {
            return workspaces.findAnyKindByWorkspaceSlug(slug);
        }
    }
}
