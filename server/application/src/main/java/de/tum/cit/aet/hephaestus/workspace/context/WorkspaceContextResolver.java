package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Shared helper that resolves {@link Workspace} entities from request-scoped {@link WorkspaceContext} payloads.
 * Ensures controllers and services consistently fail with {@link EntityNotFoundException} when the workspace
 * referenced in the URL is missing.
 */
@Component
public class WorkspaceContextResolver {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceContextResolver(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    /**
     * Resolves the workspace referenced by the current context.
     * Prefers lookup by ID when available, falls back to slug otherwise.
     */
    public Workspace requireWorkspace(WorkspaceContext context) {
        Objects.requireNonNull(context, "WorkspaceContext must not be null");
        if (context.id() != null) {
            return requireWorkspace(context.id());
        }
        String slug = context.slug();
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Workspace context does not provide an id or slug");
        }
        return workspaceRepository
                .findByWorkspaceSlug(slug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", slug));
    }

    /**
     * Resolves a team workspace by its primary key, for an instance admin route that names it outside its path. A
     * personal space answers as a workspace that does not exist.
     */
    public Workspace requireTeamWorkspace(Long workspaceId) {
        Workspace workspace = requireWorkspace(workspaceId);
        if (workspace.isPersonal()) {
            throw new EntityNotFoundException("Workspace", workspaceId.toString());
        }
        return workspace;
    }

    /** Resolves a workspace by its primary key. */
    public Workspace requireWorkspace(Long workspaceId) {
        Objects.requireNonNull(workspaceId, "Workspace id must not be null");
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
    }
}
