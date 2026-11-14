package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Optional;

/** Workspaces as the source providers find them, without a lock. A returned workspace is for reading and for handing on. */
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

    /** Whether the instance has any workspace. */
    boolean anyExists();

    /** Some workspace of the instance, for bootstrapping a single-workspace setup. */
    Optional<Workspace> findAny();
}
