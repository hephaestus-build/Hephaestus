package de.tum.cit.aet.hephaestus.practices.feedback.inapp;

import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawal;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkLabels;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
     * How many cards one read returns. The practice surface is a short list of practices to work on, not a log;
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
    private final ReviewRunLookup reviewRunLookup;
    private final ReactionRepository reactionRepository;
    private final FeedbackWithdrawalRepository withdrawalRepository;
    private final Clock clock;

    /**
     * The current developer's practice pages.
     *
     * <p>Not {@code readOnly}: opening a card is what delivers it, and the flip is recorded here. This
     * lane is the only one whose delivery we can observe rather than infer, because we own the surface;
     * marking feedback delivered when it was written would enter text nobody opened into the ledger as
     * received. A user view reads without delivering: an administrator opening a card is not the
     * recipient opening it.
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
        boolean delivering = UserViewContextHolder.get() == null;
        if (delivering) {
            // First, so what this read shows and what it records as delivered agree with any withdrawal.
            feedbackRepository.lockPreparedInAppForRecipient(workspaceId, recipientUserId);
        }
        // Every readable row, not a page of them: a run of closed cards must not crowd an older open one off it.
        List<Feedback> readable = feedbackRepository.findReadableInAppForRecipient(workspaceId, recipientUserId);
        Reads reads = reads(workspaceId, readable);
        Map<UUID, WorkResolution> resolutions = feedbackEvidence.workResolutions(
                workspaceId,
                recipientUserId,
                awaitingTheWork(readable, reads, now),
                reads.evidence(),
                reads.practiceChangedAt(),
                now);
        List<Slot> page = page(readable, reads, resolutions, now);
        List<UUID> toMarkDelivered = page.stream()
                .map(Slot::feedback)
                .filter(feedback -> delivering && feedback.getDeliveryState() == FeedbackDeliveryState.PREPARED)
                .map(Feedback::getId)
                .toList();
        if (!toMarkDelivered.isEmpty()) {
            feedbackRepository.markInAppDelivered(workspaceId, toMarkDelivered, now);
        }
        return cards(workspaceId, page);
    }

    /**
     * How many cards each developer's practice pages show open now: on the page, neither closed nor withdrawn, by
     * the rule {@link #getInAppFeedback} pages by, read without delivering anything. Every developer is read in one
     * pass rather than one read each; a developer with nothing readable counts none.
     */
    @Transactional(readOnly = true)
    public Map<Long, Integer> countOpen(Long workspaceId, Collection<Long> recipientUserIds) {
        if (recipientUserIds.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        List<Feedback> readable = feedbackRepository.findReadableInAppForRecipients(workspaceId, recipientUserIds);
        Reads reads = reads(workspaceId, readable);
        Map<UUID, WorkResolution> resolutions = feedbackEvidence.workResolutionsOfRecipients(
                workspaceId, awaitingTheWork(readable, reads, now), reads.evidence(), reads.practiceChangedAt(), now);
        Map<Long, List<Feedback>> byRecipient =
                readable.stream().collect(Collectors.groupingBy(Feedback::getRecipientUserId));
        Map<Long, Integer> open = new LinkedHashMap<>();
        for (Long recipientUserId : recipientUserIds) {
            open.put(recipientUserId, (int)
                    page(byRecipient.getOrDefault(recipientUserId, List.of()), reads, resolutions, now).stream()
                            .filter(Slot::isOpen)
                            .count());
        }
        return open;
    }

    /**
     * What the card rule reads besides the rows and the work, keyed by feedback id.
     *
     * @param withdrawnAt when each row with an open withdrawal was withdrawn
     * @param evidence the evidence each row may still show; a row absent here is no card
     * @param practiceChangedAt when the practice behind a row's evidence changed its review rules
     * @param responses the recipient's answer that stands on a row
     */
    private record Reads(
            Map<UUID, Instant> withdrawnAt,
            Map<UUID, List<Observation>> evidence,
            Map<UUID, Instant> practiceChangedAt,
            Map<UUID, FeedbackResponseDTO> responses) {}

    private Reads reads(Long workspaceId, List<Feedback> readable) {
        Map<UUID, Instant> withdrawnAt = withdrawnAt(workspaceId, readable);
        // A withdrawn card nobody was shown is not on the page at all; one already shown says it was withdrawn.
        List<UUID> rows = readable.stream()
                .filter(feedback -> !withdrawnAt.containsKey(feedback.getId())
                        || feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                .map(Feedback::getId)
                .toList();
        Map<UUID, List<Observation>> evidence = feedbackEvidence.visibleEvidence(workspaceId, rows);
        Map<UUID, FeedbackResponseDTO> responses = evidence.isEmpty()
                ? Map.of()
                : reactionRepository.findCurrentResponses(workspaceId, evidence.keySet()).stream()
                        .collect(Collectors.toMap(
                                CurrentResponseRow::getFeedbackId,
                                row -> FeedbackResponseDTO.from(row.getFeedbackId(), row)));
        return new Reads(withdrawnAt, evidence, feedbackEvidence.practiceChangedAt(evidence), responses);
    }

    /**
     * The rows the work could still close. A card the developer or its practice closed long enough ago is off the
     * page whatever the work says, since the work can only close it earlier; leaving it out keeps the work read to
     * the cards that remain.
     */
    private static List<Feedback> awaitingTheWork(List<Feedback> readable, Reads reads, Instant now) {
        return readable.stream()
                .filter(feedback -> reads.evidence().containsKey(feedback.getId()))
                .filter(feedback -> !reads.withdrawnAt().containsKey(feedback.getId()))
                .filter(feedback -> {
                    FeedbackClosure closedWithoutTheWork = FeedbackClosure.of(
                            null,
                            InAppFeedbackEvidence.resolvedByDeveloperAt(
                                    reads.responses().get(feedback.getId())),
                            reads.practiceChangedAt().get(feedback.getId()));
                    return stillOnThePage(closedWithoutTheWork == null ? null : closedWithoutTheWork.at(), now);
                })
                .toList();
    }

    /** One card's place on a page: its row, what it rests on, and how it stopped being open, if it did. */
    private record Slot(
            Feedback feedback,
            List<Observation> evidence,
            @Nullable Instant withdrawnAt,
            WorkResolution resolution,
            @Nullable FeedbackResponseDTO response,
            @Nullable Instant practiceChangedAt,
            @Nullable FeedbackClosure closure) {

        boolean isOpen() {
            return withdrawnAt == null && closure == null;
        }

        /** When the card stopped being open: withdrawn, or closed. */
        @Nullable
        Instant closedAt() {
            if (withdrawnAt != null) {
                return withdrawnAt;
            }
            return closure == null ? null : closure.at();
        }

        /**
         * A withdrawal is news as of when it happened, so it ranks by that rather than behind every card written
         * since.
         */
        Instant pageTime() {
            return withdrawnAt != null ? withdrawnAt : feedback.getCreatedAt();
        }
    }

    /**
     * The cards one recipient's page shows, newest first: every readable row with evidence left to
     * show, open or closed for less than {@link #CLOSED_CARD_STAYS}, at most {@link #MAX_CARDS}. Pure over what the
     * caller read, so the page a developer reads and the open count another page shows of it are one rule.
     *
     * @param readable the recipient's readable rows, newest first
     */
    private static List<Slot> page(
            List<Feedback> readable, Reads reads, Map<UUID, WorkResolution> resolutions, Instant now) {
        return readable.stream()
                .filter(feedback -> !reads.withdrawnAt().containsKey(feedback.getId())
                        || feedback.getDeliveryState() == FeedbackDeliveryState.DELIVERED)
                // Hidden, not deleted. Feedback whose evidence source's authorization was withdrawn must stop
                // being shown, but the ledger still records that we said it, which is the whole point of a
                // ledger. Feedback whose practice changed its review rules stays, closed, and the card says so.
                .filter(feedback -> reads.evidence().containsKey(feedback.getId()))
                .map(feedback -> slot(feedback, reads, resolutions))
                .filter(slot -> stillOnThePage(slot.closedAt(), now))
                // Stable, so every other card keeps the rows' newest-first order.
                .sorted(Comparator.comparing(Slot::pageTime).reversed())
                .limit(MAX_CARDS)
                .toList();
    }

    private static Slot slot(Feedback feedback, Reads reads, Map<UUID, WorkResolution> resolutions) {
        UUID id = feedback.getId();
        List<Observation> evidence = Objects.requireNonNull(reads.evidence().get(id));
        Instant withdrawnAt = reads.withdrawnAt().get(id);
        if (withdrawnAt != null) {
            return new Slot(feedback, evidence, withdrawnAt, WorkResolution.NONE, null, null, null);
        }
        WorkResolution resolution = resolutions.getOrDefault(id, WorkResolution.NONE);
        FeedbackResponseDTO response = reads.responses().get(id);
        Instant practiceChangedAt = reads.practiceChangedAt().get(id);
        return new Slot(
                feedback,
                evidence,
                null,
                resolution,
                response,
                practiceChangedAt,
                FeedbackClosure.of(
                        resolution.resolvedAt(),
                        InAppFeedbackEvidence.resolvedByDeveloperAt(response),
                        practiceChangedAt));
    }

    /** {@code page} as cards; one lookup names every piece of work on it, the evidence and the clean work alike. */
    private List<InAppFeedbackDTO> cards(Long workspaceId, List<Slot> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        Map<UUID, Target> targets = reviewRunLookup.findTargets(
                workspaceId,
                page.stream()
                        .flatMap(slot -> Stream.concat(
                                slot.evidence().stream().map(Observation::getAgentJobId),
                                slot.resolution().cleanWork().stream().map(Work::jobId)))
                        .collect(Collectors.toSet()));
        return page.stream()
                .map(slot -> slot.withdrawnAt() != null
                        ? withdrawnCard(slot.feedback(), slot.evidence(), slot.withdrawnAt())
                        : toCard(slot, targets))
                .toList();
    }

    /** When each of {@code rows} with an open withdrawal was withdrawn. */
    private Map<UUID, Instant> withdrawnAt(Long workspaceId, List<Feedback> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        return withdrawalRepository
                .findActiveFor(workspaceId, rows.stream().map(Feedback::getId).toList())
                .stream()
                .collect(Collectors.toMap(FeedbackWithdrawal::getFeedbackId, FeedbackWithdrawal::getWithdrawnAt));
    }

    /** The notice left in a withdrawn card's place: its practice, and when it was withdrawn. */
    private static InAppFeedbackDTO withdrawnCard(Feedback feedback, List<Observation> evidence, Instant withdrawnAt) {
        Practice practice = evidence.getFirst().getPractice();
        PracticeGroup group = practice.getGroup();
        return new InAppFeedbackDTO(
                feedback.getId(),
                practice.getName(),
                null,
                null,
                practice.getSlug(),
                practice.getName(),
                group == null ? null : group.getSlug(),
                group == null ? null : group.getName(),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                List.of(),
                feedback.getCreatedAt(),
                feedback.getDeliveredAt(),
                WorkResolution.CLEAN_NEEDED,
                List.of(),
                null,
                null,
                null,
                withdrawnAt);
    }

    /** Open, or closed for less than {@link #CLOSED_CARD_STAYS}. */
    private static boolean stillOnThePage(@Nullable Instant closedAt, Instant now) {
        return closedAt == null || !closedAt.plus(CLOSED_CARD_STAYS).isBefore(now);
    }

    private static InAppFeedbackDTO toCard(Slot slot, Map<UUID, Target> targets) {
        Feedback feedback = slot.feedback();
        List<Observation> evidence = slot.evidence();
        WorkResolution resolution = slot.resolution();
        FeedbackClosure closure = slot.closure();
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
                feedback.getCreatedAt(),
                feedback.getDeliveredAt(),
                WorkResolution.CLEAN_NEEDED,
                resolution.cleanWork().stream()
                        .map(work -> new InAppCleanWorkDTO(
                                ReviewedWorkLabels.ref(work.kind(), work.id(), targets.get(work.jobId())), work.at()))
                        .toList(),
                closure == null ? null : closure.at(),
                closure == null ? null : closure.by(),
                slot.response(),
                null);
    }
}
