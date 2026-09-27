package de.tum.cit.aet.hephaestus.activity.overview;

import java.util.Set;

/**
 * Whose activity to read: the actors, and the teams whose repository and label settings narrow it. No team
 * means the whole workspace.
 */
public record ActivityScope(long workspaceId, Set<Long> actorIds, Set<Long> teamIds) {
    boolean inTeams() {
        return !teamIds.isEmpty();
    }
}
