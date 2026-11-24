package de.tum.cit.aet.hephaestus.workspace.context;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceKind;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable request-scoped context containing workspace metadata and user roles. Used for
 * isolation, authorization, and observability (MDC enrichment).
 *
 * @param id Workspace internal ID
 * @param slug Workspace URL-safe slug
 * @param displayName Workspace display name
 * @param accountType GitHub account type (ORG or USER)
 * @param installationId GitHub App installation ID (nullable; pulled from the active {@code GITHUB}
 *     connection by the resolver)
 * @param roles Set of workspace roles for the current user
 * @param kind Whether this is a team workspace or a personal space
 */
public record WorkspaceContext(
        Long id,
        String slug,
        String displayName,
        @Nullable AccountType accountType,
        @Nullable Long installationId,
        Set<WorkspaceRole> roles,
        WorkspaceKind kind) {

    /** A team workspace's context, as a provider sync builds it: only a team workspace has providers. */
    public WorkspaceContext(
            Long id,
            String slug,
            String displayName,
            @Nullable AccountType accountType,
            @Nullable Long installationId,
            Set<WorkspaceRole> roles) {
        this(id, slug, displayName, accountType, installationId, roles, WorkspaceKind.TEAM);
    }

    /**
     * Builds a context from a {@link Workspace} plus a pre-resolved {@code installationId}
     * (typically pulled from {@code ConnectionService.findActiveGitHubAppConfig(...)} at the call
     * site so the context record itself stays free of Spring service dependencies).
     */
    public static WorkspaceContext fromWorkspace(
            Workspace workspace, @Nullable Set<WorkspaceRole> roles, @Nullable Long installationId) {
        return new WorkspaceContext(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getAccountType(),
                installationId,
                roles != null ? roles : Set.of(),
                workspace.getKind());
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
