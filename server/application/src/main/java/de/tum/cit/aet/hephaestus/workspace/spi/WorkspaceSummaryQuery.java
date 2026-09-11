package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

public interface WorkspaceSummaryQuery {
    Optional<WorkspaceSummary> findById(long workspaceId);

    /** Keyed by id; an unknown id is absent. */
    Map<Long, WorkspaceSummary> findAllByIds(Collection<Long> workspaceIds);

    record WorkspaceSummary(
            long id, @NonNull String slug, @NonNull String displayName) {}
}
