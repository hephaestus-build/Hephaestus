package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dev-only: writes in-app feedback with a history, so a local seed can show every state a card takes on the
 * Practice profile. {@link InAppFeedbackPreparer} writes a card as a review prepares it, now and unread; a demo
 * also needs cards prepared weeks ago, read, and answered. The rows are the ones the preparer writes, through
 * the same repositories, with the body layout of {@link InAppFeedbackBody} and the thread key of
 * {@link FeedbackThreadKey}, and the answer is the row the response endpoint writes.
 *
 * <p>The caller chooses every id, so it can find and remove what it wrote. Exists only while
 * {@code hephaestus.dev.seed-enabled} is set.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.dev.seed-enabled", havingValue = "true")
public class DevInAppFeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ObservationRepository observationRepository;
    private final ReactionRepository reactionRepository;

    public DevInAppFeedbackService(
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            ObservationRepository observationRepository,
            ReactionRepository reactionRepository) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.observationRepository = observationRepository;
        this.reactionRepository = reactionRepository;
    }

    /**
     * One card as the developer's page would hold it.
     *
     * @param agentJobId the review whose cycle prepared the card
     * @param evidence the problem observations the card stands on, in the order the card cites them; each must
     *     be about the recipient and on the card's practice
     * @param deliveredAt when the recipient first opened it; {@code null} for a card still unread
     * @param response the recipient's answer; only on a card they opened
     */
    public record Card(
            UUID id,
            UUID agentJobId,
            Long recipientUserId,
            String practiceSlug,
            String headline,
            String message,
            String nextStep,
            Instant createdAt,
            @Nullable Instant deliveredAt,
            List<UUID> evidence,
            @Nullable Response response) {}

    /** The recipient's answer, as one response row holds it. */
    public record Response(
            UUID id,
            Instant at,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable FeedbackResolution resolution,
            @Nullable String explanation) {}

    /**
     * Writes the cards, their evidence and their answers in one transaction. Cards of one job take the next
     * positions in the job's in-app band, in the order given.
     *
     * @return the ids of the cards written
     */
    @Transactional
    public List<UUID> write(Long workspaceId, List<Card> cards) {
        Map<UUID, Observation> evidence = observationRepository
                .findAllByIdInAndWorkspaceId(
                        cards.stream().flatMap(card -> card.evidence().stream()).toList(), workspaceId)
                .stream()
                .collect(Collectors.toMap(Observation::getId, Function.identity()));
        // Every card is checked before any is written, so a refused request leaves nothing behind.
        for (Card card : cards) {
            check(card, evidence);
        }
        Map<UUID, Integer> positions = new HashMap<>();
        for (Card card : cards) {
            int position = positions.merge(card.agentJobId(), 1, Integer::sum) - 1;
            Feedback feedback = feedbackRepository.save(Feedback.builder()
                    .id(card.id())
                    .agentJobId(card.agentJobId())
                    .workspaceId(workspaceId)
                    .recipientUserId(card.recipientUserId())
                    .aboutUserId(card.recipientUserId())
                    .channel(FeedbackChannel.IN_APP)
                    .position(FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE + position)
                    .deliveryState(
                            card.deliveredAt() == null
                                    ? FeedbackDeliveryState.PREPARED
                                    : FeedbackDeliveryState.DELIVERED)
                    .source(FeedbackSource.AGENT)
                    .body(InAppFeedbackBody.render(card.headline(), card.message(), card.nextStep()))
                    .threadKey(FeedbackThreadKey.forPractice(
                            card.practiceSlug(), card.recipientUserId(), FeedbackChannel.IN_APP))
                    .proposedPracticeSlugs(List.of(card.practiceSlug()))
                    .createdAt(card.createdAt())
                    .deliveredAt(card.deliveredAt())
                    .build());
            int ordinal = 0;
            for (UUID observationId : card.evidence()) {
                feedbackObservationRepository.insertIfAbsent(
                        feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), ordinal++);
            }
            Response response = card.response();
            if (response != null) {
                reactionRepository.save(Reaction.builder()
                        .id(response.id())
                        .feedback(feedback)
                        .reactorUserId(card.recipientUserId())
                        .usefulness(response.usefulness())
                        .resolution(response.resolution())
                        .explanation(response.explanation())
                        .createdAt(response.at())
                        .build());
            }
        }
        return cards.stream().map(Card::id).toList();
    }

    /** A card the page could not have: evidence from elsewhere, or an answer to a card nobody opened. */
    private static void check(Card card, Map<UUID, Observation> evidence) {
        if (card.evidence().isEmpty()) {
            throw invalid(card, "cites no observation");
        }
        for (UUID observationId : card.evidence()) {
            Observation observation = evidence.get(observationId);
            if (observation == null) {
                throw invalid(card, "cites observation " + observationId + ", which is not in this workspace");
            }
            if (!card.recipientUserId().equals(observation.getAboutUserId())
                    || !card.practiceSlug().equals(observation.getPractice().getSlug())) {
                throw invalid(card, "cites observation " + observationId + ", which is about other work");
            }
        }
        if (card.response() != null && card.deliveredAt() == null) {
            throw invalid(card, "has an answer but was never opened");
        }
    }

    private static ResponseStatusException invalid(Card card, String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Card " + card.id() + " " + reason);
    }
}
