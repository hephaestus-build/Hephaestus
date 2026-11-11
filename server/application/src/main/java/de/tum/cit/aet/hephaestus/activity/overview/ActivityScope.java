package de.tum.cit.aet.hephaestus.activity.overview;

import java.util.Set;

/**
 * Whose activity to read: the actors, and the teams whose repository and label settings narrow it. No team
 * means the whole workspace.
 */
public record ActivityScope(
        long workspaceId,
        Set<Long> actorIds,
        Set<Long> teamIds,
        Set<Long> repositoryIds,
        boolean publicOnly,
        WithoutAccount withoutAccount) {

    /** Which contributors without an account that chose to show a public read includes. A member read includes all. */
    public enum WithoutAccount {
        SHOWN,
        HIDDEN,
        /** Only them, to count those that a public page does not name. */
        ONLY
    }

    public ActivityScope(long workspaceId, Set<Long> actorIds, Set<Long> teamIds, Set<Long> repositoryIds) {
        this(workspaceId, actorIds, teamIds, repositoryIds, false, WithoutAccount.SHOWN);
    }

    public ActivityScope(long workspaceId, Set<Long> actorIds, Set<Long> teamIds) {
        this(workspaceId, actorIds, teamIds, Set.of());
    }

    static ActivityScope published(long workspaceId, Set<Long> repositoryIds, WithoutAccount withoutAccount) {
        return new ActivityScope(workspaceId, Set.of(), Set.of(), repositoryIds, true, withoutAccount);
    }

    ActivityScope withoutAccount(WithoutAccount mode) {
        return new ActivityScope(workspaceId, actorIds, teamIds, repositoryIds, publicOnly, mode);
    }

    boolean inTeams() {
        return !teamIds.isEmpty();
    }
}
