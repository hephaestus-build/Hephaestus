package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Trigger initial / full / on-demand data sync for a workspace.
 *
 * <p>Lets workspace lifecycle code (activation, repository-monitor changes, post-install
 * provisioning) drive vendor-specific sync without importing the vendor's sync service
 * directly. One impl per SCM kind; the workspace caller picks the impl by looking up the
 * active connection kind.
 *
 * <p>Implementations are expected to be idempotent and reentrant — the workspace caller
 * may invoke them multiple times across the activation + monitor + admin-trigger paths.
 */
public interface WorkspaceDataSyncTrigger {
    /** The SCM kind this trigger targets. */
    IntegrationKind kind();

    /**
     * Runs a full sync for every repository monitored by the given workspace.
     *
     * <p>For GitHub this means: organization + teams + per-repo (labels, milestones, issues,
     * PRs, comments) + workspace relationships (issue types, dependencies, sub-issues).
     * For GitLab: initialization, then the scope sync that every GitLab sync runs, as an
     * {@code INITIAL} sync; the GitLab implementation states when it skips the scope sync and what an
     * {@code INITIAL} sync leaves out. The exact scope is the implementation's contract.
     */
    void syncAllRepositories(long workspaceId);

    /**
     * Brings a workspace up to date when the server starts. {@code fullSync} carries the startup
     * full-sync setting; without it, an implementation runs only the work that must not wait for
     * the next sync, such as reconciling which repositories the workspace monitors.
     */
    default void syncOnStartup(long workspaceId, boolean fullSync) {
        if (fullSync) {
            syncAllRepositories(workspaceId);
        }
    }

    /**
     * Runs a sync for a single newly-monitored repository identified by sync-target id.
     *
     * <p>Used when a repository is added to monitoring after the workspace is already
     * active. Implementations may dispatch to an async executor; the contract is fire-
     * and-forget.
     */
    void syncSingleSyncTarget(long syncTargetId);
}
