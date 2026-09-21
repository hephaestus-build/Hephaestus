package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeFeedbackDeliveryPolicy;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.config.FeedbackLaneExecutor;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.JsonNode;

/**
 * Turns a finished review's composed process-level messages into IN_APP feedback units.
 *
 * <p>A failure here is logged and the feedback the developer already received is unaffected.
 *
 * <p>Late rather than lost. The event is delivered once and a submission to a saturated executor is
 * rejected outright, so this listener is not a guarantee of anything on its own — it is the fast path.
 * {@link #prepare} records that the lane ran, and {@code FeedbackLanePreparationSweeper} runs it for
 * every finished job that carries no such record.
 *
 * <p>Writes none of the words. The composer names a practice; the server — not the model — resolves
 * which of that person's measurements stand behind it, because evidence a model asserts about itself is
 * not evidence.
 */
@Component
public class InAppCompositionListener {

    private static final Logger log = LoggerFactory.getLogger(InAppCompositionListener.class);

    /**
     * Ceiling on how many of a person's measurements of one practice are read to weigh a pattern. The
     * router only needs to count distinct artifacts and check provenance, so the whole window is never
     * required and an unbounded read would grow with how much work somebody did.
     */
    private static final int MAX_EVIDENCE_PER_PRACTICE = 50;

    private final AgentJobRepository agentJobRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;
    private final FeedbackCompositionResultParser resultParser;
    private final InAppFeedbackPreparer preparer;
    private final PracticeFeedbackDeliveryPolicy deliveryPolicy;
    private final PreviousInAppFeedback previousInAppFeedback;

    public InAppCompositionListener(
            AgentJobRepository agentJobRepository,
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            ObservationVisibilityPolicy visibilityPolicy,
            WorkspaceReviewDefaultsProvider workspaceDefaults,
            FeedbackCompositionResultParser resultParser,
            InAppFeedbackPreparer preparer,
            PracticeFeedbackDeliveryPolicy deliveryPolicy,
            PreviousInAppFeedback previousInAppFeedback) {
        this.agentJobRepository = agentJobRepository;
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
        this.visibilityPolicy = visibilityPolicy;
        this.workspaceDefaults = workspaceDefaults;
        this.resultParser = resultParser;
        this.preparer = preparer;
        this.deliveryPolicy = deliveryPolicy;
        this.previousInAppFeedback = previousInAppFeedback;
    }

    @Async(FeedbackLaneExecutor.BEAN_NAME)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPracticeDetectionDelivered(PracticeDetectionDeliveredEvent event) {
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
     * Route this job's composed messages to their recipients, then record that the lane ran.
     *
     * <p>Throws rather than logging, because its second caller is the recovery sweeper: a failure that
     * leaves the mark unwritten is what makes the sweeper try again, and a caught one would look
     * identical to success and retire the job from the sweep for good.
     *
     * <p>The mark is written on every non-exceptional path, including a job that composed nothing —
     * which is the common case, since composition is a stage a review may skip. "Nothing to prepare" is
     * an answer, and a lane that has answered must stop being swept.
     *
     * @return units newly prepared by this call (0 on a re-run, and 0 when nothing was composed)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int prepare(UUID agentJobId, Long workspaceId) {
        return prepare(agentJobId, agentJobId, workspaceId);
    }

    /** Prepare source observations using a separate composition job's output. */
    public int prepare(UUID sourceJobId, UUID compositionJobId, Long workspaceId) {
        int prepared = route(sourceJobId, compositionJobId, workspaceId);
        agentJobRepository.markInAppPrepared(compositionJobId, Instant.now());
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
        // One recipient in practice — a review job files its observations against one person — but
        // read rather than assumed, so a kind that ever files against several does not silently
        // deliver all of their patterns to whoever happened to be first.
        List<Long> recipients = observationRepository.findSubjectUserIdsByAgentJobId(agentJobId, workspaceId);
        // Every recipient's units share this job's id, and (agent_job_id, position) is unique, so each
        // recipient gets its own slice of the band. The query orders by user id, so a re-run assigns
        // the same slices and the idempotency guard still recognises what it already wrote.
        int positionBase = FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE;
        int prepared = 0;
        for (Long recipient : recipients) {
            if (recipient == null) {
                continue;
            }
            prepared += prepareFor(outputJobId, workspaceId, recipient, messages, positionBase);
            positionBase += messages.size();
        }
        return prepared;
    }

    /**
     * This lane's share of one composition turn. The stage writes for every open surface in a single
     * turn, so the units arrive together and each lane takes its own: a unit addressed to the merge
     * request or to the mentor is not this producer's to route, and silently treating one as an in-app
     * card would put a note about one line on a surface that exists to talk about habits.
     *
     * <p>A {@code WITHHOLD} unit is dropped here without a row, because on this lane the reason is
     * always a property of the evidence — there was no pattern, nobody was owed anything — and a refusal
     * that is a property of the evidence is not a withholding to explain.
     */
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
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy();
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofDays(InAppFeedbackRouter.PATTERN_WINDOW_DAYS));
        List<InAppFeedbackPreparer.RoutedMessage> routed = new ArrayList<>(messages.size());
        for (ComposedInAppMessage message : messages) {
            // A new card about a habit starts where the previous card about it left off: work that resolved
            // the last card, or that the developer answered it over, or that the last card already cited
            // while it stays open, is never cited again. An open previous card is what the new one replaces.
            Optional<PreviousInAppFeedback.Previous> previous =
                    previousInAppFeedback.find(workspaceId, recipientUserId, message.practiceSlug());
            Instant since = previous.map(PreviousInAppFeedback.Previous::nextEvidenceSince)
                    .filter(start -> start.isAfter(windowStart))
                    .orElse(windowStart);
            List<Observation> evidence = visibleEvidence(workspaceId, recipientUserId, message.practiceSlug(), since);
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
            // Only the problems are bound, not the whole window the router read: the card renders these
            // rows as "the pieces of work this habit was observed on", so a piece of work where the
            // practice went WELL must never appear among them.
            routed.add(new InAppFeedbackPreparer.RoutedMessage(
                    message,
                    decision,
                    InAppFeedbackRouter.problemsIn(evidence),
                    previous.filter(PreviousInAppFeedback.Previous::isOpen)
                            .map(PreviousInAppFeedback.Previous::id)
                            .orElse(null)));
        }
        return preparer.prepare(agentJobId, workspaceId, recipientUserId, List.copyOf(routed), positionBase);
    }

    /**
     * The recipient's own measurements of one practice, narrowed to what may be shown at all.
     *
     * <p>The visibility gate runs here and again at read: a claim measured under review rules the
     * practice has since replaced, or one whose evidence source lost its authorization, must stop being
     * cited. Composition freezes text; it must not freeze permission.
     */
    private List<Observation> visibleEvidence(
            Long workspaceId, Long recipientUserId, String practiceSlug, Instant since) {
        List<Observation> candidates = observationRepository.findRecentForSubjectAndPractice(
                workspaceId, recipientUserId, practiceSlug, since, PageRequest.of(0, MAX_EVIDENCE_PER_PRACTICE));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<UUID> visible =
                visibilityPolicy.permitsAll(workspaceId, candidates, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return candidates.stream().filter(o -> visible.contains(o.getId())).toList();
    }

    /**
     * The practice's effective autonomy, resolved through the practice → group → workspace chain from a
     * projection rather than by walking associations — the same reason {@code FeedbackChannelRouter}
     * projects it: the routing rule must not depend on whether the caller holds a session.
     */
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

    /**
     * Whose conduct the practice behind this evidence judges, read off its occasion. Asked with no
     * signal, so every occasion the practice declares is considered: the question here is whether this
     * practice can be about somebody other than the person we are about to show it to, not which run
     * produced it.
     */
    private ActorRole subjectRole(List<Observation> evidence) {
        return evidence.stream()
                .map(Observation::getPractice)
                .filter(Objects::nonNull)
                .findFirst()
                .map(practice -> PracticeBinding.subjectRoleOf(practice.getBindings(), null))
                .orElse(ActorRole.AUTHOR);
    }
}
