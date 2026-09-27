package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.config.FeedbackLaneExecutor;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drives the conversational-feedback router + preparer off {@link PracticeDetectionDeliveredEvent}. Runs
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} so it never blocks the delivery path and reads the
 * cycle's observations only after they are committed, in its own {@code REQUIRES_NEW} transaction. A failure
 * here is logged, never propagated: the feedback the developer already received is unaffected.
 *
 * <p>Late rather than lost. The event is delivered once and a submission to a saturated executor is
 * rejected outright, so this listener is not a guarantee of anything on its own — it is the fast path.
 * {@link #prepare} records that the lane ran, and {@code FeedbackLanePreparationSweeper} runs it for
 * every finished job that carries no such record. Reviewer attribution is not built (ADR 0021), so every
 * pass is driven with {@link RoutingContext#author()}.
 */
@Component
public class ConversationalDeliveryListener {

    private static final Logger log = LoggerFactory.getLogger(ConversationalDeliveryListener.class);

    private final ObservationRepository observationRepository;
    private final FeedbackChannelRouter router;
    private final ConversationalFeedbackPreparer preparer;
    private final OutboundEgressGuard egressGuard;
    private final AgentJobRepository agentJobRepository;
    private final FeedbackCompositionResultParser resultParser;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;

    public ConversationalDeliveryListener(
            ObservationRepository observationRepository,
            FeedbackChannelRouter router,
            ConversationalFeedbackPreparer preparer,
            OutboundEgressGuard egressGuard,
            AgentJobRepository agentJobRepository,
            FeedbackCompositionResultParser resultParser,
            PracticeFeedbackDeliveryPolicy deliveryPolicy) {
        this.observationRepository = observationRepository;
        this.router = router;
        this.preparer = preparer;
        this.egressGuard = egressGuard;
        this.agentJobRepository = agentJobRepository;
        this.resultParser = resultParser;
        this.deliveryPolicy = deliveryPolicy;
    }

    @Async(FeedbackLaneExecutor.BEAN_NAME)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPracticeDetectionDelivered(PracticeDetectionDeliveredEvent event) {
        try {
            prepare(event.agentJobId(), event.agentJobId(), event.workspaceId());
        } catch (RuntimeException e) {
            log.warn(
                    "Conversational routing/prepare failed (delivery unaffected): jobId={}, error={}",
                    event.agentJobId(),
                    e.toString());
        }
    }

    /**
     * Route and prepare this cycle, then record that the lane ran.
     *
     * <p>Throws rather than logging, because its second caller is the recovery sweeper: a failure that
     * leaves the mark unwritten is what makes the sweeper try again, and a caught one would look
     * identical to success and retire the job from the sweep for good.
     *
     * <p>The mark is written on every non-exceptional path, including the ones that prepare nothing.
     * "Nothing to prepare" is an answer, and a lane that has answered must stop being swept.
     *
     * @return units newly prepared by this call (0 on a re-run, and 0 when nothing was admitted)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int prepare(UUID agentJobId, Long workspaceId) {
        return prepare(agentJobId, agentJobId, workspaceId);
    }

    /** Prepare source observations using a separate composition job's output. */
    public int prepare(UUID sourceJobId, UUID compositionJobId, Long workspaceId) {
        int prepared = route(sourceJobId, compositionJobId, workspaceId);
        agentJobRepository.markInChatPrepared(compositionJobId, Instant.now());
        return prepared;
    }

    private int route(UUID agentJobId, UUID outputJobId, Long workspaceId) {
        if (!egressGuard.deliveryAllowed("prepare-conversational-feedback")) {
            log.debug("Conversational preparation suppressed: jobId={}", agentJobId);
            return 0;
        }
        AgentJob sourceJob = agentJobRepository.findById(agentJobId).orElse(null);
        if (sourceJob == null || !deliveryPolicy.allowsComposition(sourceJob, DeliveryPolicySurface.CONVERSATION)) {
            return 0;
        }
        List<Observation> observations = observationRepository.findByAgentJobId(agentJobId, workspaceId);
        if (observations.isEmpty()) {
            return 0;
        }
        List<Observation> admitted = router.admit(observations, workspaceId, RoutingContext.author());
        return preparer.prepare(outputJobId, workspaceId, admitted, composedMoves(outputJobId));
    }

    /**
     * This lane's share of the job's one composition turn. The stage writes for every open surface in a
     * single turn, so the units arrive together and each lane takes its own: a unit addressed to the merge
     * request or to the developer's page is not this producer's to route.
     *
     * <p>Empty means there is no mentor-ready brief to queue. It covers both an intentionally quiet turn and
     * a composition stage that was skipped, failed, or malformed; neither is permission to turn a severity
     * ranking into feedback. A later review may compose a complete brief for the same practice.
     */
    private List<ComposedFeedbackUnit> composedMoves(UUID agentJobId) {
        AgentJob job = agentJobRepository.findById(agentJobId).orElse(null);
        return job == null ? List.of() : resultParser.parse(job.getOutput(), FeedbackChannel.IN_CHAT);
    }
}
