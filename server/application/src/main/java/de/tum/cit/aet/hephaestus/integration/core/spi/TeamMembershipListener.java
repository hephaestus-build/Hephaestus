package de.tum.cit.aet.hephaestus.integration.core.spi;

/**
 * Listener for team membership sync events.
 * <p>
 * integration.scm defines this interface and calls it after a team sync completes.
 * Consuming modules implement this to reconcile workspace-level state with
 * the synced team membership graph (e.g. ensure that every contributor who
 * appears in a team under the workspace is also listed as a workspace member).
 * <p>
 * This follows the Dependency Inversion Principle: integration.scm depends on the
 * abstraction it defines, not on consuming module concepts.
 */
public interface TeamMembershipListener {
    /**
     * Called after a scheduled sync listed a root group's whole team graph.
     * <p>
     * Implementations reconcile downstream state (e.g., workspace memberships) with the stored team memberships.
     * Only when {@link TeamsSyncedEvent#complete()} are all of them current; otherwise a team whose listing was
     * incomplete kept its old members, which may be kept but prove nobody else's removal.
     *
     * @param event the team sync completed event data
     */
    default void onTeamMembershipsSynced(TeamsSyncedEvent event) {}

    /**
     * Event data for team sync completion.
     *
     * @param scopeId           the workspace/scope ID under which teams were synced
     * @param rootGroupFullPath the root group full path whose descendants were synced
     *                          (stored as {@code Team.organization} for every team
     *                          created under this root)
     * @param providerId        the instance the teams were synced from; another instance can have the same path
     * @param complete          whether every team's members were listed completely in this sync
     */
    record TeamsSyncedEvent(Long scopeId, String rootGroupFullPath, Long providerId, boolean complete) {}
}
