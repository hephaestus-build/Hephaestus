package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Transactional
public class ConversationalDeliveryReconciler {

    private static final Logger log = LoggerFactory.getLogger(ConversationalDeliveryReconciler.class);

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackPlacementRepository feedbackPlacementRepository;
    private final ObservationRepository observationRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;

    public ConversationalDeliveryReconciler(
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            FeedbackPlacementRepository feedbackPlacementRepository,
            ObservationRepository observationRepository,
            ObservationVisibilityPolicy visibilityPolicy) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.feedbackPlacementRepository = feedbackPlacementRepository;
        this.observationRepository = observationRepository;
        this.visibilityPolicy = visibilityPolicy;
    }

    public int reconcile(long workspaceId, long recipientUserId, UUID chatMessageId, List<UUID> linkedObservationIds) {
        Instant now = Instant.now();
        for (Observation observation :
                admitted(workspaceId, linkedObservationIds).values()) {
            UUID observationId = observation.getId();
            List<UUID> feedbackIds = feedbackObservationRepository.findPreparedConversationFeedbackIdsByObservation(
                    workspaceId, recipientUserId, observationId);
            if (feedbackIds.isEmpty()) {
                continue;
            }
            if (observation.getRecurrenceKey() != null
                    && feedbackRepository.existsDeliveredInContextForRecurrenceKey(
                            workspaceId, recipientUserId, observation.getRecurrenceKey())) {
                continue;
            }
            UUID feedbackId = feedbackIds.get(0);
            int flipped = feedbackRepository.markConversationDelivered(feedbackId, now);
            if (flipped == 1) {
                Feedback unit = feedbackRepository.getReferenceById(feedbackId);
                feedbackPlacementRepository.save(FeedbackPlacement.builder()
                        .feedback(unit)
                        .placementType(PlacementType.CONVERSATION_TURN)
                        .chatMessageId(chatMessageId)
                        .createdAt(now)
                        .build());
                log.info(
                        "Conversational feedback delivered: feedbackId={}, chatMessageId={}, recipient={}",
                        feedbackId,
                        chatMessageId,
                        recipientUserId);
                return 1;
            }
        }
        return 0;
    }

    /**
     * The linked observations this turn may act on: the observations this workspace can read whose claim and
     * evidence the visibility policy still permits for mentoring, keyed by id, in the mentor's emission
     * order.
     *
     * <p>{@code linkedObservationIds} is the mentor's raw tool output — {@code link_observation} carries whatever
     * UUID the model emitted, and nothing between the tool call and here checks it against the observations the
     * turn's context was actually served ({@code PiEventToUiChunkTranslator} only parses it as a UUID). This
     * gate is therefore the only thing standing between a model-chosen id and a write to the feedback
     * ledger, and <em>both</em> endings of a turn are ledger writes — one flips a unit to DELIVERED, the
     * other burns it to SUPPRESSED — so both are gated here rather than at one call site.
     *
     * <p>A refused id is left alone rather than settled. Refusal is not always terminal (an evidence
     * authorization the source catalog withdrew can come back; a claim measured against superseded review
     * rules cannot), and nothing ever writes a unit back to PREPARED, so settling on the first refusal would
     * spend the developer's coaching on a condition that may lift tomorrow. The unit behind a refused id is
     * still settled, by {@link ConversationFeedbackTtlSweeper} at the end of its window.
     *
     * <p>Two queries for the whole turn, not one per linked id — nothing caps how many observations a mentor
     * turn links (TranslatorState appends a row per {@code link_observation} tool call).
     */
    private Map<UUID, Observation> admitted(long workspaceId, List<UUID> linkedObservationIds) {
        if (linkedObservationIds == null || linkedObservationIds.isEmpty()) {
            return Map.of();
        }
        // Emission order, deduplicated: the first linked observation that survives every gate wins the turn, so
        // the order the mentor linked them in is part of the answer and must survive the batching below.
        Set<UUID> observationIds = new LinkedHashSet<>(linkedObservationIds);
        List<Observation> rows = observationRepository.findAllByIdInAndWorkspaceId(observationIds, workspaceId);
        Map<UUID, Observation> byId = new HashMap<>(rows.size());
        for (Observation observation : rows) {
            byId.put(observation.getId(), observation);
        }
        Set<UUID> visible = visibilityPolicy.permitsForNewDelivery(
                workspaceId, byId.values(), SourceUsePurpose.CONVERSATIONAL_MENTORING);
        Map<UUID, Observation> admitted = new LinkedHashMap<>();
        for (UUID observationId : observationIds) {
            Observation observation = byId.get(observationId);
            // Absent from either batch means refused.
            if (observation != null && visible.contains(observationId)) {
                admitted.put(observationId, observation);
            }
        }
        return admitted;
    }

    /**
     * Silent Mode permanently suppresses the unit instead of postponing it: the mentor had this to say and
     * the instance stopped it, which is a different answer to "why was nothing said" than "it is still
     * queued". Walks the same {@link #admitted} observations {@link #reconcile} may act on: a linked id that
     * subsystem is not allowed to raise is not one Silent Mode gets to claim it stopped. It does not repeat
     * that method's recurrence-key rule — "already said inline" is about what to say next, and nothing is
     * being said.
     */
    public int suppressForSilentMode(long workspaceId, long recipientUserId, List<UUID> linkedObservationIds) {
        for (UUID observationId : admitted(workspaceId, linkedObservationIds).keySet()) {
            List<UUID> feedbackIds = feedbackObservationRepository.findPreparedConversationFeedbackIdsByObservation(
                    workspaceId, recipientUserId, observationId);
            if (feedbackIds.isEmpty()) {
                continue;
            }
            UUID feedbackId = feedbackIds.get(0);
            if (feedbackRepository.markConversationSuppressedBySilentMode(feedbackId) == 1) {
                log.info(
                        "Conversational feedback suppressed by instance Silent Mode: feedbackId={}, recipient={}",
                        feedbackId,
                        recipientUserId);
                return 1;
            }
        }
        return 0;
    }
}
