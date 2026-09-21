package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppFeedbackDTO;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.CurrentResponseRow;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution;
import de.tum.cit.aet.hephaestus.practices.observation.trend.WorkResolution.Work;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the current developer's own practice feedback, and records that they read it.
 *
 * <p>Self-scoped with no way to ask about anybody else: the recipient is resolved from the security
 * context, never from a parameter. That is not a convenience — the pull is what makes this surface safe,
 * and a {@code userId} parameter would turn a private surface into a roster.
 */
@Service
@RequiredArgsConstructor
public class InAppFeedbackService {

    /**
     * How many cards one read returns. The practice surface is a short list of habits to work on, not a log;
     * a developer who has to scroll it has been handed more than they can act on.
     */
    private static final int MAX_CARDS = 20;

    /**
     * How long a closed card stays on the page after it closed — resolved by the work or by the developer,
     * or closed because the practice changed. A closed card is a record the developer already saw; the
     * profile is a list of habits to work on, not a log, and the ledger keeps the record. Applied when the
     * page is read, never by touching the row, so the ledger and the operator surfaces still have it.
     */
    static final Duration CLOSED_CARD_STAYS = Duration.ofDays(30);

    private final FeedbackRepository feedbackRepository;
    private final InAppFeedbackEvidence feedbackEvidence;
    private final UserRepository userRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final ReactionRepository reactionRepository;
    private final Clock clock;

    /**
     * The current developer's practice pages.
     *
     * <p>Not {@code readOnly}: opening a card is what delivers it, and the flip is recorded here. This
     * lane is the only one whose delivery we can observe rather than infer, because we own the surface;
     * marking feedback delivered when it was written would enter text nobody opened into the ledger as
     * received.
     *
     * @return empty when the caller is not a synced developer, exactly as the sibling read models do —
     *     a first login before any work has been mirrored is not an error
     */
    @Transactional
    public List<InAppFeedbackDTO> getInAppFeedback(Long workspaceId) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            return List.of();
        }
        Long recipientUserId = currentUser.get().getId();
        List<Feedback> prepared = feedbackRepository.findReadableInAppForRecipient(
                workspaceId, recipientUserId, PageRequest.of(0, MAX_CARDS));
        if (prepared.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Observation>> evidenceByFeedback = feedbackEvidence.visibleEvidence(
                workspaceId, prepared.stream().map(Feedback::getId).toList());
        // Hidden, not deleted. Feedback whose evidence source's authorization was withdrawn must stop
        // being shown, but the ledger still records that we said it, which is the whole point of a
        // ledger. Feedback whose practice changed its review rules stays, closed, and the card says so.
        List<Feedback> shown = prepared.stream()
                .filter(feedback -> evidenceByFeedback.containsKey(feedback.getId()))
                .toList();
        Map<UUID, WorkResolution> resolutionByFeedback =
                feedbackEvidence.workResolutions(workspaceId, recipientUserId, shown, evidenceByFeedback);
        // One lookup names every piece of work on the page, the evidence and the clean work alike.
        Map<UUID, Target> targets = reviewRunTargetLookup.findByJobIds(
                workspaceId,
                Stream.concat(
                                evidenceByFeedback.values().stream()
                                        .flatMap(List::stream)
                                        .map(Observation::getAgentJobId),
                                resolutionByFeedback.values().stream()
                                        .flatMap(resolution -> resolution.cleanWork().stream())
                                        .map(Work::jobId))
                        .collect(Collectors.toSet()));
        // The developer's own answers, one query for the page: the same current response the response
        // endpoint returns for one card.
        Map<UUID, FeedbackResponseDTO> responseByFeedback = reactionRepository
                .findCurrentResponses(
                        recipientUserId,
                        workspaceId,
                        shown.stream().map(Feedback::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        CurrentResponseRow::getFeedbackId, row -> FeedbackResponseDTO.from(row.getFeedbackId(), row)));
        Instant now = clock.instant();
        List<InAppFeedbackDTO> cards = shown.stream()
                .map(feedback -> toCard(
                        feedback,
                        Objects.requireNonNull(evidenceByFeedback.get(feedback.getId())),
                        resolutionByFeedback.getOrDefault(feedback.getId(), WorkResolution.NONE),
                        responseByFeedback.get(feedback.getId()),
                        targets))
                .filter(card -> stillOnThePage(card, now))
                .toList();
        Set<UUID> onThePage = cards.stream().map(InAppFeedbackDTO::id).collect(Collectors.toSet());
        List<UUID> toMarkDelivered = shown.stream()
                .filter(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .map(Feedback::getId)
                .filter(onThePage::contains)
                .toList();
        if (!toMarkDelivered.isEmpty()) {
            feedbackRepository.markInAppDelivered(workspaceId, toMarkDelivered, now);
        }
        return List.copyOf(cards);
    }

    /** Open, or closed for less than {@link #CLOSED_CARD_STAYS}. */
    private static boolean stillOnThePage(InAppFeedbackDTO card, Instant now) {
        FeedbackResponseDTO response = card.response();
        Instant closedAt = InAppFeedbackEvidence.closedAt(
                card.resolvedByWorkAt(),
                response != null
                                && response.resolution() != null
                                && response.resolution().resolves()
                        ? response.respondedAt()
                        : null,
                card.practiceChangedAt());
        return closedAt == null || !closedAt.plus(CLOSED_CARD_STAYS).isBefore(now);
    }

    private static InAppFeedbackDTO toCard(
            Feedback feedback,
            List<Observation> evidence,
            WorkResolution resolution,
            @Nullable FeedbackResponseDTO response,
            Map<UUID, Target> targets) {
        Practice practice = evidence.getFirst().getPractice();
        PracticeGroup group = practice.getGroup();
        String headline = InAppFeedbackBody.headlineOf(feedback.getBody());
        return new InAppFeedbackDTO(
                feedback.getId(),
                headline != null ? headline : practice.getName(),
                InAppFeedbackBody.messageOf(feedback.getBody()),
                InAppFeedbackBody.nextStepOf(feedback.getBody()),
                practice.getSlug(),
                practice.getName(),
                group == null ? null : group.getSlug(),
                group == null ? null : group.getName(),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                evidence.stream()
                        .map(observation ->
                                InAppEvidenceDTO.from(observation, targets.get(observation.getAgentJobId())))
                        .toList(),
                (int) evidence.stream()
                        .map(Work::of)
                        .map(Work.Key::of)
                        .distinct()
                        .count(),
                feedback.getCreatedAt(),
                feedback.getDeliveredAt(),
                WorkResolution.CLEAN_NEEDED,
                resolution.cleanWork().stream()
                        .map(work -> ReviewedWorkLabels.ref(work.kind(), work.id(), targets.get(work.jobId())))
                        .toList(),
                resolution.resolvedAt(),
                InAppFeedbackEvidence.practiceChangedAt(evidence),
                response);
    }
}
