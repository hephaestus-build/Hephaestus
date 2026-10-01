package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunTargetRow;
import de.tum.cit.aet.hephaestus.agent.job.ReviewableArtifactOwnershipRepository.ReviewedWorkTitle;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The work a run reviewed as every surface names it: the run's snapshot, titled by what the mirror calls the work
 * now while it still holds it. The developer's pages and the admin job and review lists all read targets here, so
 * one review is never listed under two titles.
 */
@Component
@RequiredArgsConstructor
class ReviewRunTargets {

    private final ReviewableArtifactOwnershipRepository artifacts;

    Target of(long workspaceId, AgentJob job) {
        Target snapshot = ReviewRunTargetMapper.from(job);
        return current(snapshot, currentTitles(workspaceId, List.of(snapshot)));
    }

    /** @return the targets by run id */
    Map<UUID, Target> of(long workspaceId, Collection<? extends ReviewRunTargetRow> rows) {
        Map<UUID, Target> snapshots = rows.stream()
                .collect(Collectors.toUnmodifiableMap(ReviewRunTargetRow::getId, ReviewRunTargetMapper::from));
        Map<Long, String> titles = currentTitles(workspaceId, snapshots.values());
        return snapshots.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> current(entry.getValue(), titles)));
    }

    /** Pull requests and issues only: other kinds' ids come from other tables. */
    private Map<Long, String> currentTitles(long workspaceId, Collection<Target> targets) {
        Set<Long> ids = targets.stream()
                .filter(ReviewRunTargets::isMirrored)
                .map(Target::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return artifacts.findCurrentTitles(workspaceId, ids).stream()
                .filter(row -> !row.getTitle().isBlank())
                .collect(Collectors.toUnmodifiableMap(ReviewedWorkTitle::getId, ReviewedWorkTitle::getTitle));
    }

    private static Target current(Target snapshot, Map<Long, String> titles) {
        Long id = snapshot.id();
        String title = id != null && isMirrored(snapshot) ? titles.get(id) : null;
        return title == null ? snapshot : snapshot.withTitle(title);
    }

    private static boolean isMirrored(Target target) {
        return target.type().equals(ArtifactKinds.PULL_REQUEST) || target.type().equals(ArtifactKinds.ISSUE);
    }
}
