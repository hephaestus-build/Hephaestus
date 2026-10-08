package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeFeedbackPreparationRequestedEvent;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.config.FeedbackLaneExecutor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

/**
 * Prepares composed IN_APP feedback and resolves its eligible supporting observations.
 *
 * <p>The asynchronous listener is the fast path. {@code FeedbackLanePreparationSweeper} retries
 * unmarked jobs within its recovery window, including jobs whose listener submission was rejected.
 */
@Component
public class InAppCompositionListener {

    private static final Logger log = LoggerFactory.getLogger(InAppCompositionListener.class);

    private final AgentJobRepository agentJobRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;
    private final FeedbackCompositionResultParser resultParser;
    private final InAppFeedbackPreparer preparer;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final @Nullable InAppSupportReader supportReader;
    private final Clock clock;

    public InAppCompositionListener(
            AgentJobRepository agentJobRepository,
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            ObjectProvider<InAppSupportReader> supportReaders,
            WorkspaceReviewDefaultsProvider workspaceDefaults,
            FeedbackCompositionResultParser resultParser,
            InAppFeedbackPreparer preparer,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            Clock clock) {
        this.agentJobRepository = agentJobRepository;
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
        this.workspaceDefaults = workspaceDefaults;
        this.resultParser = resultParser;
        this.preparer = preparer;
        this.deliveryPolicy = deliveryPolicy;
        this.supportReader = supportReaders.getIfAvailable();
        this.clock = clock;
    }

    @Async(FeedbackLaneExecutor.BEAN_NAME)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPracticeFeedbackPreparationRequested(PracticeFeedbackPreparationRequestedEvent event) {
        try {
            prepare(event.agentJobId(), event.agentJobId(), event.workspaceId());
        } catch (RuntimeException e) {
            log.warn(
                    "In-app composition routing failed (delivery unaffected): jobId={}, error={}",
                    event.agentJobId(),
                    e.toString());
        }
    }

    /**
     * Records completion even when nothing is prepared. Exceptions propagate so recovery can retry
     * jobs whose completion mark was not committed.
     *
     * @return newly prepared units
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int prepare(UUID agentJobId, Long workspaceId) {
        return prepare(agentJobId, agentJobId, workspaceId);
    }

    /** Prepare source observations using a separate composition job's output. */
    public int prepare(UUID sourceJobId, UUID compositionJobId, Long workspaceId) {
        int prepared = route(sourceJobId, compositionJobId, workspaceId);
        agentJobRepository.markInAppPrepared(compositionJobId, clock.instant());
        return prepared;
    }

    private int route(UUID agentJobId, UUID outputJobId, Long workspaceId) {
        AgentJob sourceJob = agentJobRepository.findById(agentJobId).orElse(null);
        if (sourceJob == null || !deliveryPolicy.allowsComposition(sourceJob, DeliveryPolicySurface.IN_APP)) {
            return 0;
        }
        AgentJob job = agentJobRepository.findById(outputJobId).orElse(null);
        if (job == null) {
            return 0;
        }
        List<ComposedInAppMessage> messages = inAppMessages(job.getOutput());
        if (messages.isEmpty()) {
            return 0;
        }
        List<Long> recipients = observationRepository.findSubjectUserIdsByAgentJobId(agentJobId, workspaceId);
        // Stable recipient ordering preserves unique (agent_job_id, position) slots across retries.
        int positionBase = FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE;
        int prepared = 0;
        for (Long recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            // The pulled page's practice authority belongs to InAppFeedbackRouter, per message.
            if (deliveryPolicy
                    .evaluateForRecipient(
                            sourceJob,
                            DeliveryPolicyStage.COMPOSITION,
                            null,
                            DeliveryPolicySurface.IN_APP,
                            recipient,
                            List.of())
                    .allowed()) {
                prepared += prepareFor(outputJobId, workspaceId, recipient, messages, positionBase);
            }
            positionBase += messages.size();
        }
        return prepared;
    }

    private List<ComposedInAppMessage> inAppMessages(@Nullable JsonNode jobOutput) {
        return resultParser.parse(jobOutput, FeedbackChannel.IN_APP).stream()
                .filter(unit -> unit.action() != ComposedFeedbackUnit.Action.WITHHOLD)
                .filter(ComposedFeedbackUnit::isComplete)
                // The composer's supersession target is not carried: which card a new one replaces is the
                // server's rule, read off the page's own view of the previous card in prepareFor.
                .map(unit -> new ComposedInAppMessage(
                        unit.practiceSlug(),
                        Objects.requireNonNull(unit.title()),
                        Objects.requireNonNull(unit.body()),
                        Objects.requireNonNull(unit.nextStep())))
                .toList();
    }

    private int prepareFor(
            UUID agentJobId,
            Long workspaceId,
            Long recipientUserId,
            List<ComposedInAppMessage> messages,
            int positionBase) {
        var reader = supportReader;
        if (reader == null) return 0;
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy();
        Instant now = clock.instant();
        List<InAppFeedbackPreparer.RoutedMessage> routed = new ArrayList<>(messages.size());
        for (ComposedInAppMessage message : messages) {
            // An open previous card is what the new one replaces.
            InAppSupportReader.Selection selection =
                    reader.select(workspaceId, recipientUserId, message.practiceSlug(), now);
            Optional<PreviousInAppFeedback.Previous> previous = selection.previous();
            List<Observation> evidence = selection.evidence();
            InAppRoutingDecision decision = InAppFeedbackRouter.route(
                    message,
                    evidence,
                    effectiveTier(evidence, workspaceId, workspaceDefault),
                    subjectRole(evidence),
                    feedbackRepository
                            .lastInAppSurfacedAt(workspaceId, recipientUserId, message.practiceSlug())
                            .orElse(null),
                    now);
            if (decision != InAppRoutingDecision.ADMIT) {
                log.debug(
                        "In-app message withheld: reason={}, practice={}, jobId={}",
                        decision,
                        message.practiceSlug(),
                        agentJobId);
            }
            // The card presents bound observations as examples of the problem, not the full review window.
            routed.add(new InAppFeedbackPreparer.RoutedMessage(
                    message,
                    decision,
                    selection.supports(),
                    previous.filter(PreviousInAppFeedback.Previous::isOpen)
                            .map(PreviousInAppFeedback.Previous::id)
                            .orElse(null)));
        }
        return preparer.prepare(agentJobId, workspaceId, recipientUserId, List.copyOf(routed), positionBase);
    }

    // Project autonomy to avoid depending on initialized entity associations.
    private @Nullable PracticeAutonomy effectiveTier(
            List<Observation> evidence, Long workspaceId, PracticeAutonomy workspaceDefault) {
        List<UUID> ids = evidence.stream()
                .map(Observation::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return null;
        }
        return observationRepository.findPracticeAutonomyFor(ids, workspaceId).stream()
                .findFirst()
                .map(row -> AutonomyResolver.resolvePractice(
                                row.getPracticeAutonomy(), row.getGroupAutonomy(), workspaceDefault)
                        .autonomy())
                .orElse(null);
    }

    private ActorRole subjectRole(List<Observation> evidence) {
        return evidence.stream()
                .map(Observation::getPractice)
                .filter(Objects::nonNull)
                .findFirst()
                .map(Practice::getSubject)
                .orElse(ActorRole.AUTHOR);
    }
}
