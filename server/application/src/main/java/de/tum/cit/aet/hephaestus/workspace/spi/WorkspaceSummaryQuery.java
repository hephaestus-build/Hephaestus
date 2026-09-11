package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

public interface WorkspaceSummaryQuery {
    Optional<WorkspaceSummary> findById(long workspaceId);

    /** The workspaces that still exist, keyed by id; a purged or unknown id is simply absent. */
    default Map<Long, WorkspaceSummary> findAllByIds(Collection<Long> workspaceIds) {
        Map<Long, WorkspaceSummary> found = new HashMap<>();
        for (Long id : workspaceIds) findById(id).ifPresent(summary -> found.put(id, summary));
        return found;
    }

    record WorkspaceSummary(
            long id, @NonNull String slug, @NonNull String displayName) {}
}
