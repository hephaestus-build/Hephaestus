package de.tum.cit.aet.hephaestus.core.event;

import java.util.List;

/**
 * A workspace loses what it holds of these exact source repositories, not only the monitor. Stopping a monitor keeps a
 * workspace's history, so this is a separate event. Published inside the monitor transaction, while the source rows
 * still exist: {@code activity_event.repository_id} is set to null when its repository goes.
 */
public record RepositoryWithdrawnFromWorkspaceEvent(long workspaceId, List<Long> repositoryIds) {
    public RepositoryWithdrawnFromWorkspaceEvent {
        repositoryIds = List.copyOf(repositoryIds);
    }
}
