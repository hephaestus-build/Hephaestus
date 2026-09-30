package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewedWorkRow;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.spi.RevisedWorkLookup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers {@link RevisedWorkLookup} from each producing run's stored capture, read in the caller's transaction so it
 * is compared with the pull request the caller holds, and with the sources automated review may use.
 */
@Component
@RequiredArgsConstructor
class RevisedWorkLookupAdapter implements RevisedWorkLookup {

    private final AgentJobRepository jobRepository;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> recordedOnOtherWork(
            long workspaceId,
            long pullRequestId,
            @Nullable String title,
            @Nullable String body,
            @Nullable String head,
            Collection<Observation> observations) {
        List<Observation> aboutThisWork = observations.stream()
                .filter(observation -> ArtifactKinds.PULL_REQUEST.equals(observation.getArtifactKind())
                        && Objects.equals(observation.getArtifactId(), pullRequestId))
                .toList();
        if (aboutThisWork.isEmpty()) {
            return Set.of();
        }
        Map<UUID, ReviewedWorkRow> captures = jobRepository
                .findReviewedWork(
                        workspaceId,
                        aboutThisWork.stream().map(Observation::getAgentJobId).collect(Collectors.toSet()),
                        ReviewedWorkComparison.DESCRIPTION_PATH,
                        ReviewedWorkComparison.CHANGE_PATH)
                .stream()
                .collect(Collectors.toMap(ReviewedWorkRow::getId, Function.identity()));
        return aboutThisWork.stream()
                .filter(observation -> ReviewedWorkComparison.of(
                                captures.get(observation.getAgentJobId()),
                                ArtifactKinds.PULL_REQUEST,
                                pullRequestId,
                                title,
                                body,
                                head,
                                SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW,
                                sourceCatalogs,
                                objectMapper)
                        .differs())
                .map(Observation::getId)
                .collect(Collectors.toSet());
    }
}
