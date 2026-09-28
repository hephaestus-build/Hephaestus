package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.ReviewableArtifactOwnershipRepository.ReviewedWorkTitle;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class ReviewRunTargetLookupAdapter implements ReviewRunTargetLookup {

    private final AgentJobRepository repository;
    private final ReviewableArtifactOwnershipRepository artifacts;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Target> findByJobIds(long workspaceId, Collection<UUID> jobIds) {
        if (jobIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Target> targets = repository.findReviewRunTargets(workspaceId, jobIds).stream()
                .collect(Collectors.toUnmodifiableMap(
                        AgentJobRepository.ReviewRunTargetRow::getId, ReviewRunTargetMapper::from));
        return withCurrentTitles(workspaceId, targets);
    }

    /**
     * The work's title as it reads now rather than as the run's metadata snapshotted it. A pull request
     * renamed after its review was reviewed under the old name, but it is not called that any more, and a
     * list of someone's own work that names it by a title the work itself contradicts is a claim we cannot
     * stand behind. The snapshot is the fallback, for work the mirror no longer holds: the run still ran on
     * something, and the name it ran under is the only name left to give it.
     *
     * <p>Only pull requests and issues: a conversation and a document are named from their own metadata,
     * and their ids come from different tables, so an id looked up here would answer for another work.
     */
    private Map<UUID, Target> withCurrentTitles(long workspaceId, Map<UUID, Target> targets) {
        Set<Long> ids = targets.values().stream()
                .filter(ReviewRunTargetLookupAdapter::isMirroredArtifact)
                .map(Target::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (ids.isEmpty()) {
            return targets;
        }
        Map<Long, String> titles = artifacts.findCurrentTitles(workspaceId, ids).stream()
                .collect(Collectors.toUnmodifiableMap(ReviewedWorkTitle::getId, ReviewedWorkTitle::getTitle));
        Map<UUID, Target> named = new HashMap<>(targets.size());
        targets.forEach((jobId, target) -> named.put(jobId, renamed(target, titles)));
        return Map.copyOf(named);
    }

    private static Target renamed(Target target, Map<Long, String> titles) {
        Long id = target.id();
        String current = isMirroredArtifact(target) && id != null ? titles.get(id) : null;
        return current == null || current.isBlank() || current.equals(target.title())
                ? target
                : new Target(
                        target.type(),
                        id,
                        target.provider(),
                        target.number(),
                        current,
                        target.repositoryName(),
                        target.channelName(),
                        target.url());
    }

    private static boolean isMirroredArtifact(Target target) {
        return target.type().equals(ArtifactKinds.PULL_REQUEST) || target.type().equals(ArtifactKinds.ISSUE);
    }
}
