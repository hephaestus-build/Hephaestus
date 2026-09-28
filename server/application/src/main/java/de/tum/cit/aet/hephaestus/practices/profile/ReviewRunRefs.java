package de.tum.cit.aet.hephaestus.practices.profile;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.DeveloperReviewRunRow;
import de.tum.cit.aet.hephaestus.practices.profile.dto.ReviewRunRefDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunFactsLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunFactsLookup.ReviewRunFacts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * How the profile names a review run: the piece of work it ran on and the facts the run recorded about
 * itself. The two are read together because every surface that names a run needs both, and a reference
 * built from one of them alone would date the run by an observation rather than by the run.
 */
@Component
@RequiredArgsConstructor
class ReviewRunRefs {

    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final ReviewRunFactsLookup reviewRunFactsLookup;

    /** The piece of work each run reviewed; a run this workspace does not own is simply absent. */
    Map<UUID, Target> targets(long workspaceId, Collection<UUID> jobIds) {
        return reviewRunTargetLookup.findByJobIds(workspaceId, jobIds);
    }

    /** One run as the profile refers to it: its own moment, the work it ran on, and how it ended. */
    ReviewRunRefDTO ref(long workspaceId, DeveloperReviewRunRow run, Map<UUID, Target> targets) {
        ReviewRunFacts facts = reviewRunFactsLookup
                .findByJobIds(workspaceId, List.of(run.getJobId()))
                .get(run.getJobId());
        return new ReviewRunRefDTO(
                run.getJobId(),
                facts == null ? run.getReviewedAt() : facts.reviewedAt(run.getReviewedAt()),
                ReviewedWorkLabels.ref(
                        ArtifactKind.of(run.getArtifactKind()), run.getArtifactId(), targets.get(run.getJobId())),
                facts == null ? null : facts.state());
    }
}
