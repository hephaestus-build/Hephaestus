package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PublicReviewEligibility {
    private final ReviewSnapshotQuery reviews;
    private final ItemDescriptorRegistry catalog;
    private final FeedbackModeQuery feedbackModes;

    public Set<UUID> publicResultIds(ReviewJob job, List<AdmittedResultSnapshot> results) {
        var review = reviews.find(job.getWorkspaceId(), job.getReviewId());
        if (review.isEmpty() || review.get().kind() != ReviewKind.ITEM) return Set.of();
        var snapshot = review.get();
        var descriptor = catalog.descriptorFor(snapshot.item().kind());
        if (descriptor.isEmpty()
                || descriptor.get().copyCapabilities().stream()
                        .noneMatch(capability ->
                                capability == CopyCapability.SUMMARY || capability == CopyCapability.LINE_NOTE))
            return Set.of();
        // An OFF practice's result stays recorded; it only cannot support public feedback.
        var modes = feedbackModes.effectiveModes(
                job.getWorkspaceId(),
                results.stream().map(result -> result.candidate().practiceId()).toList());
        return results.stream()
                .filter(result -> result.candidate().developerId() != null
                        && result.candidate().itemId() == snapshot.item().itemId()
                        && snapshot.practices().stream()
                                .anyMatch(practice -> practice.practiceId()
                                                == result.candidate().practiceId()
                                        && practice.practiceVersionId()
                                                == result.candidate().practiceVersionId()
                                        && practice.definition().about() == AboutRole.AUTHOR)
                        && modes.getOrDefault(result.candidate().practiceId(), FeedbackMode.OFF) != FeedbackMode.OFF)
                .map(AdmittedResultSnapshot::id)
                .collect(Collectors.toSet());
    }
}
