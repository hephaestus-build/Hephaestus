package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;

/**
 * Team workspaces as the source providers find them, without a lock. A returned workspace is for reading and for
 * handing on. A personal space has no provider and bootstraps nothing, so no method here returns one.
 */
public interface WorkspaceLookup {
    /** The workspace, unlocked, for a source provider's sync to hand on. */
    Optional<Workspace> find(long workspaceId);

    /** The active workspaces. */
    List<Workspace> active();

    Optional<Workspace> findBySlug(String slug);

    /** The one workspace that binds the organization. */
    Optional<Workspace> findByOrganization(long organizationId);

    /** The workspaces whose source account has this login, ignoring case. */
    List<Workspace> findByAccountLogin(String accountLogin);

    /** Whether the instance has any team workspace. */
    boolean anyExists();

    /** Some team workspace of the instance, for bootstrapping a single-workspace setup. */
    Optional<Workspace> findAny();
}
