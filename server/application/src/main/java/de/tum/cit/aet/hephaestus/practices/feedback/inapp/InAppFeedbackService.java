package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.dto.InAppCleanWorkDTO;
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
     * a developer who has to scroll it has been handed more than they can act on. The limit bounds the answer,
     * not the read: whether a card is still on the page is known only once it is read, so each read loads every
     * readable card the developer keeps and its cost grows with them.
     */
    static final int MAX_CARDS = 20;

    /**
     * How long a card stays on the page after it closed ({@link FeedbackClosure}): a closed card is a record
     * the developer already saw, and the ledger keeps the record. Applied when the page is read, never by
     * touching the row, so the ledger and the operator surfaces still have it.
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
        Instant now = clock.instant();
        // Every readable row, not a page of them: a run of closed cards must not crowd an older open one off it.
        List<Feedback> rows = feedbackRepository.findReadableInAppForRecipient(workspaceId, recipientUserId);
        List<InAppFeedbackDTO> onThePage = readCards(workspaceId, recipientUserId, rows, now).stream()
                .filter(card -> stillOnThePage(card.closedAt(), now))
                .limit(MAX_CARDS)
                .toList();
        Set<UUID> prepared = rows.stream()
                .filter(feedback -> feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .map(Feedback::getId)
                .collect(Collectors.toSet());
        List<UUID> toMarkDelivered = onThePage.stream()
                .map(InAppFeedbackDTO::id)
                .filter(prepared::contains)
                .toList();
        if (!toMarkDelivered.isEmpty()) {
            feedbackRepository.markInAppDelivered(workspaceId, toMarkDelivered, now);
        }
        return onThePage;
    }

    /** The rows as cards, in the rows' order; a row with no evidence left to show is no card. */
    private List<InAppFeedbackDTO> readCards(Long workspaceId, Long recipientUserId, List<Feedback> rows, Instant now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Observation>> evidenceByFeedback = feedbackEvidence.visibleEvidence(
                workspaceId, rows.stream().map(Feedback::getId).toList());
        // Hidden, not deleted. Feedback whose evidence source's authorization was withdrawn must stop
        // being shown, but the ledger still records that we said it, which is the whole point of a
        // ledger. Feedback whose practice changed its review rules stays, closed, and the card says so.
        List<Feedback> shown = rows.stream()
                .filter(feedback -> evidenceByFeedback.containsKey(feedback.getId()))
                .toList();
        if (shown.isEmpty()) {
            return List.of();
        }
        Map<UUID, Instant> practiceChangedAt = feedbackEvidence.practiceChangedAt(evidenceByFeedback);
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
        // A card the developer or its practice closed long enough ago is off the page whatever the work says,
        // since the work can only close it earlier; leaving it out keeps the work read to the cards that remain.
        List<Feedback> candidates = shown.stream()
                .filter(feedback -> {
                    FeedbackClosure closedWithoutTheWork = FeedbackClosure.of(
                            null,
                            InAppFeedbackEvidence.resolvedByDeveloperAt(responseByFeedback.get(feedback.getId())),
                            practiceChangedAt.get(feedback.getId()));
                    return stillOnThePage(closedWithoutTheWork == null ? null : closedWithoutTheWork.at(), now);
                })
                .toList();
        Map<UUID, WorkResolution> resolutionByFeedback = feedbackEvidence.workResolutions(
                workspaceId, recipientUserId, candidates, evidenceByFeedback, practiceChangedAt, now);
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
        return candidates.stream()
                .map(feedback -> toCard(
                        feedback,
                        Objects.requireNonNull(evidenceByFeedback.get(feedback.getId())),
                        resolutionByFeedback.getOrDefault(feedback.getId(), WorkResolution.NONE),
                        responseByFeedback.get(feedback.getId()),
                        practiceChangedAt.get(feedback.getId()),
                        targets))
                .toList();
    }

    /** Open, or closed for less than {@link #CLOSED_CARD_STAYS}. */
    private static boolean stillOnThePage(@Nullable Instant closedAt, Instant now) {
        return closedAt == null || !closedAt.plus(CLOSED_CARD_STAYS).isBefore(now);
    }

    private static InAppFeedbackDTO toCard(
            Feedback feedback,
            List<Observation> evidence,
            WorkResolution resolution,
            @Nullable FeedbackResponseDTO response,
            @Nullable Instant practiceChangedAt,
            Map<UUID, Target> targets) {
        Practice practice = evidence.getFirst().getPractice();
        PracticeGroup group = practice.getGroup();
        String headline = InAppFeedbackBody.headlineOf(feedback.getBody());
        FeedbackClosure closure = FeedbackClosure.of(
                resolution.resolvedAt(), InAppFeedbackEvidence.resolvedByDeveloperAt(response), practiceChangedAt);
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
                feedback.getCreatedAt(),
                feedback.getDeliveredAt(),
                WorkResolution.CLEAN_NEEDED,
                resolution.cleanWork().stream()
                        .map(work -> new InAppCleanWorkDTO(
                                ReviewedWorkLabels.ref(work.kind(), work.id(), targets.get(work.jobId())), work.at()))
                        .toList(),
                closure == null ? null : closure.at(),
                closure == null ? null : closure.by(),
                response);
    }
}
