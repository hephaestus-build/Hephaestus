package de.tum.cit.aet.hephaestus.activity.overview;

import java.util.Set;

/**
 * Whose activity to read: the actors, and the teams whose repository and label settings narrow it. No team
 * means the whole workspace.
 */
public record ActivityScope(long workspaceId, Set<Long> actorIds, Set<Long> teamIds, Set<Long> repositoryIds) {
    public ActivityScope(long workspaceId, Set<Long> actorIds, Set<Long> teamIds) {
        this(workspaceId, actorIds, teamIds, Set.of());
    }

    boolean inTeams() {
        return !teamIds.isEmpty();
    }
}
