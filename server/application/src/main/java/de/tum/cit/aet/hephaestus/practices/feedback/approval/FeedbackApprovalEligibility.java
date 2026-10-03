package de.tum.cit.aet.hephaestus.practices.feedback.approval;

import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeedbackApprovalEligibility {

    private final PracticeRepository practiceRepository;
    private final WorkspaceReviewDefaultsProvider defaultsProvider;
    private final WorkspaceRepository workspaceRepository;
    private final SilentModeQuery silentModeQuery;
    private final FeedbackRepository feedbackRepository;

    @Transactional(readOnly = true)
    public boolean isEligible(Long workspaceId, UUID feedbackId) {
        var feedback = feedbackRepository
                .findByIdAndWorkspaceId(feedbackId, workspaceId)
                .orElse(null);
        if (feedback == null || feedback.getProposedPracticeSlugs().isEmpty()) return false;
        var slugs = Set.copyOf(feedback.getProposedPracticeSlugs());
        var practices = practiceRepository.findByWorkspaceIdAndSlugIn(workspaceId, slugs);
        if (practices.size() != slugs.size()) return false;
        PracticeAutonomy workspaceDefault =
                defaultsProvider.forWorkspace(workspaceId).defaultAutonomy();
        var authorities = practices.stream()
                .map(practice -> AutonomyResolver.effectiveAutonomyOf(practice, workspaceDefault))
                .toList();
        return (authorities.stream().noneMatch(authority -> authority == PracticeAutonomy.OFF)
                && authorities.stream().anyMatch(authority -> authority == PracticeAutonomy.HUMAN_APPROVAL));
    }

    @Transactional(readOnly = true)
    public @Nullable FeedbackSuppressionReason brakeOnDelivery(Long workspaceId) {
        if (silentModeQuery.isSilentModeEngaged()) {
            return FeedbackSuppressionReason.INSTANCE_SILENCED;
        }
        boolean paused = workspaceRepository
                .findById(workspaceId)
                .map(workspace -> workspace.getReviewSettings().getDeliveryStatus() == PracticeDeliveryStatus.PAUSED)
                .orElse(false);
        return paused ? FeedbackSuppressionReason.WORKSPACE_DELIVERY_PAUSED : null;
    }
}
