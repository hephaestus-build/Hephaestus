package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable request-scoped context containing workspace metadata and user roles.
 * Used for isolation, authorization, and observability (MDC enrichment).
 *
 * @param id Workspace internal ID
 * @param slug Workspace URL-safe slug
 * @param displayName Workspace display name
 * @param accountType GitHub account type (ORG or USER)
 * @param installationId GitHub App installation ID (nullable; pulled from the active
 *                       {@code GITHUB} Connection by the resolver — Workspace no longer
 *                       carries the column directly)
 * @param publiclyViewable Whether the workspace allows public read access
 * @param roles Set of workspace roles for the current user
 */
public record WorkspaceContext(
        Long id,
        String slug,
        String displayName,
        @Nullable AccountType accountType,
        @Nullable Long installationId,
        boolean publiclyViewable,
        Set<WorkspaceRole> roles) {
    /**
     * Builds a context from a {@link Workspace} plus a pre-resolved
     * {@code installationId} (typically pulled from
     * {@code ConnectionService.findActiveGitHubAppConfig(...)} at the call site so the
     * context record itself stays free of Spring service dependencies).
     */
    public static WorkspaceContext fromWorkspace(
            Workspace workspace, @Nullable Set<WorkspaceRole> roles, @Nullable Long installationId) {
        return new WorkspaceContext(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getAccountType(),
                installationId,
                Boolean.TRUE.equals(workspace.getIsPubliclyViewable()),
                roles != null ? roles : Set.of());
    }

    /**
     * Check if the current user has a specific role in this workspace.
     *
     * @param role Role to check
     * @return true if user has the specified role
     */
    public boolean hasRole(WorkspaceRole role) {
        return roles.contains(role);
    }

    /**
     * Check if the current user has any membership (any role) in this workspace.
     *
     * @return true if user has at least one role
     */
    public boolean hasMembership() {
        return !roles.isEmpty();
    }
}
