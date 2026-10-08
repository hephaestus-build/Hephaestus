package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The evidence a new IN_APP card about one practice may stand on: the window from the previous card's cutoff, the
 * bounded recent read, and the delivery-purpose visibility. {@link InAppFeedbackRouter#problemsIn} picks the citable
 * occurrences from it. Selection only: routing and preparation decide whether a card is written. Worker admission and
 * server preparation read the same selection, so it exists in every runtime role.
 */
@Component
public class InAppSupportReader {

    private static final int MAX_EVIDENCE_PER_PRACTICE = 50;

    private final ObservationRepository observationRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final PreviousInAppFeedback previousInAppFeedback;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final Clock clock;

    public InAppSupportReader(
            ObservationRepository observationRepository,
            ObservationVisibilityPolicy visibilityPolicy,
            PreviousInAppFeedback previousInAppFeedback,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            Clock clock) {
        this.observationRepository = observationRepository;
        this.visibilityPolicy = visibilityPolicy;
        this.previousInAppFeedback = previousInAppFeedback;
        this.deliveryPolicy = deliveryPolicy;
        this.clock = clock;
    }

    /** The previous card about the practice, if any, and the evidence a new card reads. */
    public record Selection(Optional<PreviousInAppFeedback.Previous> previous, List<Observation> evidence) {
        public List<Observation> supports() {
            return InAppFeedbackRouter.problemsIn(evidence);
        }
    }

    public Selection select(Long workspaceId, Long recipientUserId, String practiceSlug, Instant now) {
        Instant windowStart = now.minus(Duration.ofDays(InAppFeedbackRouter.PATTERN_WINDOW_DAYS));
        // A new card about a practice starts where the previous card about it left off: work that resolved
        // the last card, or that the developer answered it over, or that the last card already cited
        // while it stays open, is never cited again.
        Optional<PreviousInAppFeedback.Previous> previous =
                previousInAppFeedback.find(workspaceId, recipientUserId, practiceSlug, now);
        Instant since =
                previous.map(card -> card.nextEvidenceSince(windowStart)).orElse(windowStart);
        return new Selection(previous, visibleEvidence(workspaceId, recipientUserId, practiceSlug, since));
    }

    /**
     * The support the private composition of an admitted run reads for IN_APP: per current NOT_MET practice, the
     * occurrences a card could cite now. The recipient and the practices come from the admitted observations.
     */
    public InAppSupportContext snapshot(AgentJob job, List<Observation> admitted) {
        Instant now = clock.instant();
        List<Observation> negatives = admitted.stream()
                .filter(observation -> observation.getOutcome() == Outcome.NOT_MET)
                .toList();
        if (negatives.isEmpty()) {
            return InAppSupportContext.complete(now, List.of());
        }
        Set<Long> recipients =
                negatives.stream().map(Observation::getAboutUserId).collect(Collectors.toSet());
        if (recipients.size() != 1) {
            // The private composition addresses one developer; a support read for several is not this context.
            return InAppSupportContext.unavailable(now);
        }
        Long recipient = recipients.iterator().next();
        if (!deliveryPolicy.allowsComposition(job, DeliveryPolicySurface.IN_APP)) {
            return InAppSupportContext.refused(now, null);
        }
        var decision = deliveryPolicy.evaluateForRecipient(
                job, DeliveryPolicyStage.COMPOSITION, null, DeliveryPolicySurface.IN_APP, recipient, List.of());
        if (!decision.allowed()) {
            return InAppSupportContext.refused(now, decision.suppressionReason());
        }
        Long workspaceId = job.getWorkspace().getId();
        List<InAppSupportContext.PracticeSupport> practices = negatives.stream()
                .map(observation -> observation.getPractice().getSlug())
                .distinct()
                .sorted()
                .map(slug -> new InAppSupportContext.PracticeSupport(
                        slug,
                        select(workspaceId, recipient, slug, now).supports().stream()
                                .map(InAppSupportContext.Occurrence::of)
                                .toList()))
                .toList();
        return InAppSupportContext.complete(now, practices);
    }

    private List<Observation> visibleEvidence(
            Long workspaceId, Long recipientUserId, String practiceSlug, Instant since) {
        List<Observation> candidates = observationRepository.findRecentForSubjectAndPractice(
                workspaceId, recipientUserId, practiceSlug, since, PageRequest.of(0, MAX_EVIDENCE_PER_PRACTICE));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<UUID> visible = visibilityPolicy.permitsForNewDelivery(
                workspaceId, candidates, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return candidates.stream().filter(o -> visible.contains(o.getId())).toList();
    }
}
