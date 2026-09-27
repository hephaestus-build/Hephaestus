package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.ConversationalFeedbackPreparer;
import de.tum.cit.aet.hephaestus.agent.handler.conversation.PracticeDetectionDeliveredEvent;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor.DiffAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository.ProviderPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorKind;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.feedback.ProposedPlacement;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class FeedbackLedgerRecorder {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLedgerRecorder.class);

    private static final int IN_CONTEXT_UNIT_ORDINAL = 0;

    /**
     * Slots per ordinal band. A band that overflows would silently address the next band's unit — the
     * {@code (agent_job_id, position)} guard would read another band's row as "already recorded" and drop the
     * write. Writers of a variable-length band must bound themselves by this.
     */
    public static final int UNIT_ORDINAL_BAND_WIDTH = 1000;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordApprovedPlacements(
            Feedback feedback, @Nullable String summaryRef, List<DeliveredSignal> inlineSignals) {
        if (summaryRef != null) {
            feedbackPlacementRepository.insertProviderPlacementIfAbsent(new ProviderPlacement(
                    UUID.randomUUID(),
                    feedback.getId(),
                    PlacementType.SUMMARY.name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    summaryRef));
        }
        for (DeliveredSignal signal : inlineSignals) {
            if (signal.disposition() == Disposition.FAILED) continue;
            DiffAnchor anchor = (DiffAnchor) signal.anchor();
            feedbackPlacementRepository.insertProviderPlacementIfAbsent(new ProviderPlacement(
                    UUID.randomUUID(),
                    feedback.getId(),
                    PlacementType.INLINE.name(),
                    (anchor.startLine() != null ? PlacementAnchorKind.RANGE : PlacementAnchorKind.LINE).name(),
                    anchor.filePath(),
                    anchor.startLine() != null ? anchor.startLine() : anchor.newLineNumber(),
                    anchor.newLineNumber(),
                    PlacementAnchorSide.NEW.name(),
                    signal.externalRef()));
        }
    }

    /** Reaction-suppressed units start here so they never collide with the live IN_CONTEXT unit (ordinal 0). */
    private static final int SUPPRESSED_UNIT_ORDINAL_BASE = 1000;

    /** Composer-withheld SUPPRESSED units start here, one band clear of the one above. */
    private static final int COMPOSER_WITHHELD_UNIT_ORDINAL_BASE = 2000;

    /**
     * PREPARED conversational units start here, one band clear of the one above. Public so
     * {@link ConversationalFeedbackPreparer} derives its positions from the one shared constant rather than
     * a second literal.
     */
    public static final int IN_CHAT_UNIT_ORDINAL_BASE = 3000;

    /**
     * Undelivered (FAILED) units start here, one band clear of the one above. One row per job records the
     * composed body a delivery attempt could not place, so an evaluator can audit what the student WOULD
     * have received.
     */
    private static final int UNDELIVERED_UNIT_ORDINAL = 4000;

    /** The gate-suppressed unit, one per job. */
    private static final int GATE_SUPPRESSED_UNIT_ORDINAL = 5000;

    /**
     * Autonomy-withheld SUPPRESSED units start here, one band clear of the one above. Public so
     * {@code InContextDeliveryGate} derives its positions from the one shared constant rather than a second
     * literal, and so it can bound itself by {@link #UNIT_ORDINAL_BAND_WIDTH}.
     */
    public static final int AUTONOMY_WITHHELD_UNIT_ORDINAL_BASE = 6000;

    /**
     * IN_APP units start here, one band clear of the one above. They share the review job's
     * {@code agent_job_id} because the process-level message is composed inside that job's run, so they
     * need a band of their own exactly as the conversational units do. Public so
     * {@code InAppFeedbackPreparer} derives its positions from the one shared constant.
     */
    public static final int IN_APP_UNIT_ORDINAL_BASE = 7000;

    private static final int APPROVAL_UNIT_ORDINAL = 8000;

    private final ObservationRepository observationRepository;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackPlacementRepository feedbackPlacementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboundEgressGuard egressGuard;
    private final PracticeFeedbackCommentFormatter commentFormatter;

    FeedbackLedgerRecorder(
            ObservationRepository observationRepository,
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            FeedbackPlacementRepository feedbackPlacementRepository,
            ApplicationEventPublisher eventPublisher,
            OutboundEgressGuard egressGuard,
            PracticeFeedbackCommentFormatter commentFormatter) {
        this.observationRepository = observationRepository;
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.feedbackPlacementRepository = feedbackPlacementRepository;
        this.eventPublisher = eventPublisher;
        this.egressGuard = egressGuard;
        this.commentFormatter = commentFormatter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            AgentJob job,
            DeliveryContent delivery,
            ArtifactKind artifact,
            List<DeliveredSignal> inlineSignals,
            @Nullable String summaryExternalRef,
            boolean inlineDelivered) {
        record(job, delivery, artifact, inlineSignals, summaryExternalRef, inlineDelivered, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWithoutConversation(
            AgentJob job,
            DeliveryContent delivery,
            ArtifactKind artifact,
            List<DeliveredSignal> inlineSignals,
            @Nullable String summaryExternalRef,
            boolean inlineDelivered) {
        record(job, delivery, artifact, inlineSignals, summaryExternalRef, inlineDelivered, false);
    }

    private void record(
            AgentJob job,
            DeliveryContent delivery,
            ArtifactKind artifact,
            List<DeliveredSignal> inlineSignals,
            @Nullable String summaryExternalRef,
            boolean inlineDelivered,
            boolean conversationalDeliveryEligible) {
        boolean summaryDelivered = summaryExternalRef != null;
        if (conversationalDeliveryEligible) {
            publishFeedbackLaneTrigger(job);
        }
        if (delivery == null) {
            return;
        }
        if (!summaryDelivered && !inlineDelivered) {
            return;
        }
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (observations.isEmpty()) {
            return;
        }
        // A package still settling records the copies it has placed so far, so a correction can reach them; a
        // later record of the same package adds what became known since, and never a second unit.
        Long workspaceId = job.getWorkspace().getId();
        Feedback feedback = feedbackRepository
                .findByAgentJobIdAndPositionAndWorkspaceId(job.getId(), IN_CONTEXT_UNIT_ORDINAL, workspaceId)
                .orElse(null);
        boolean created = feedback == null;
        Instant now = Instant.now();
        if (feedback == null) {
            Observation any = observations.get(0);
            long recipientUserId = any.getAboutUserId();
            String feedbackThreadKey = feedbackThreadKeyFor(any);
            UUID supersedesId = summaryDelivered
                    ? feedbackPlacementRepository
                            .findLatestDeliveredSummary(feedbackThreadKey)
                            .map(FeedbackPlacement::getFeedbackId)
                            .orElse(null)
                    : null;
            feedback = feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(workspaceId)
                    .artifactKind(any.getArtifactKind())
                    .artifactId(any.getArtifactId())
                    // recipient == about for the author-side catalogue (single source); they diverge only for
                    // reviewer-audience practices (ADR 0021).
                    .recipientUserId(recipientUserId)
                    .aboutUserId(recipientUserId)
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(IN_CONTEXT_UNIT_ORDINAL)
                    .deliveryState(FeedbackDeliveryState.DELIVERED)
                    .body(summaryDelivered ? delivery.mrNote() : null)
                    .source(FeedbackSource.AGENT)
                    .threadKey(feedbackThreadKey)
                    .replacesId(supersedesId)
                    .createdAt(now)
                    .deliveredAt(now)
                    .build());
            if (supersedesId != null) {
                feedbackRepository.supersedeDelivered(workspaceId, supersedesId);
            }
        }

        // Reaction suppression already wrote its REACTED_* units before this runs and does NOT delete the
        // Observation, so exclude those rows here or they would be bound a second time.
        Set<UUID> alreadySuppressed =
                new HashSet<>(feedbackObservationRepository.findObservationIdsSuppressedForJob(job.getId()));

        // The composer's drops this run, addressed by occurrence key (one observation each).
        Map<String, FeedbackSuppressionReason> withheldByKey = delivery.withheld().stream()
                .collect(Collectors.toMap(
                        PracticeDetectionResultParser.WithheldObservation::occurrenceKey,
                        PracticeDetectionResultParser.WithheldObservation::reason));
        List<Observation> composerWithheld = observations.stream()
                .filter(f -> withheldByKey.containsKey(f.getOccurrenceKey()))
                .filter(f -> !alreadySuppressed.contains(f.getId()))
                .toList();
        // The DELIVERED unit binds nothing that was withheld: composer-withheld this run + already-suppressed.
        Set<UUID> excludedIds =
                composerWithheld.stream().map(Observation::getId).collect(Collectors.toCollection(HashSet::new));
        excludedIds.addAll(alreadySuppressed);

        // Bind the observations behind the placements that reached the developer: BAD (the problems surfaced)
        // lead as PRIMARY, GOOD strengths as SUPPORTING. Each placement carries its own evidence, so a line note
        // that never landed binds nothing and an unsaid strength stays open to the other channels. A package
        // persisted before contributors were recorded keeps the earlier rule.
        // Severity is null for a positive observation (ADR 0022) — sort it after any problem (least severe).
        Set<String> deliveredInlineKeys = deliveredKeys(inlineSignals);
        List<String> summaryContributors = delivery.summaryContributors();
        Set<String> landed = summaryContributors == null
                ? Set.of()
                : evidenceOf(delivery, summaryDelivered ? summaryContributors : List.of(), deliveredInlineKeys);
        List<Observation> assessed = observations.stream()
                .filter(f -> summaryContributors == null
                        ? summaryDelivered || deliveredInlineKeys.contains("observation:" + f.getOccurrenceKey())
                        : landed.contains(f.getOccurrenceKey()))
                .filter(f -> (f.getAssessmentStatus() == AssessmentStatus.ASSESSED))
                .filter(f -> !excludedIds.contains(f.getId()))
                // Stable order matching the composer's prioritisation, and the same ObservationOrder it uses:
                // severity, then how much of the work the observation's citations span, then id — so the persisted
                // PRIMARY ordinal of equal-severity problems is reproducible across re-runs rather than flapping
                // with the repository's findByAgentJobId iteration order.
                .sorted(ObservationOrder.worstFirst())
                .toList();
        int ordinal = created ? 0 : feedbackObservationRepository.countForFeedback(workspaceId, feedback.getId());
        for (Observation f : assessed) {
            EvidenceRole role = f.getOutcome() == Outcome.NEGATIVE ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            ordinal += feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal);
        }

        if (summaryExternalRef != null) {
            feedbackPlacementRepository.insertProviderPlacementIfAbsent(new ProviderPlacement(
                    UUID.randomUUID(),
                    feedback.getId(),
                    PlacementType.SUMMARY.name(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    summaryExternalRef));
        }

        int inlinePlacementCount = 0;
        if (ArtifactKinds.hasInlineLane(artifact) && inlineDelivered) {
            for (DiffNote note : delivery.diffNotes()) {
                DeliveredSignal signal = matchSignal(note, inlineSignals);
                if (signal == null || signal.disposition() == Disposition.FAILED) {
                    continue;
                }
                inlinePlacementCount +=
                        feedbackPlacementRepository.insertProviderPlacementIfAbsent(new ProviderPlacement(
                                UUID.randomUUID(),
                                feedback.getId(),
                                PlacementType.INLINE.name(),
                                (note.endLine() != null ? PlacementAnchorKind.RANGE : PlacementAnchorKind.LINE).name(),
                                note.filePath(),
                                note.startLine(),
                                note.endLine(),
                                PlacementAnchorSide.NEW.name(),
                                signal.externalRef()));
            }
        }

        // Deliberately in THIS transaction, uncaught: a DELIVERED unit whose withheld siblings are missing is a
        // ledger that reads complete and is not — worse than no ledger at all. Both land, or neither does.
        recordComposerWithheld(job, composerWithheld, withheldByKey);

        log.info(
                "Feedback ledger recorded: jobId={}, unit={}, observations={}, inlinePlacements={}, feedbackThreadKey={}",
                job.getId(),
                feedback.getId(),
                assessed.size(),
                inlinePlacementCount,
                feedback.getThreadKey());
    }

    /** The summary's evidence plus the evidence of every line note named by {@code noteKeys}. */
    private static Set<String> evidenceOf(DeliveryContent delivery, List<String> summary, Set<String> noteKeys) {
        Set<String> evidence = new HashSet<>(summary);
        for (DiffNote note : delivery.diffNotes()) {
            List<String> contributors = note.contributors();
            if (note.deliveryKey() != null && noteKeys.contains(note.deliveryKey()) && contributors != null) {
                evidence.addAll(contributors);
            }
        }
        return evidence;
    }

    /** The observations behind the named line notes; for a pre-upgrade package, the ones the keys name. */
    private static List<Observation> behindNotes(
            List<Observation> observations, DeliveryContent delivery, Set<String> noteKeys) {
        if (delivery.summaryContributors() == null) {
            return observations.stream()
                    .filter(f -> noteKeys.contains("observation:" + f.getOccurrenceKey()))
                    .toList();
        }
        Set<String> evidence = evidenceOf(delivery, List.of(), noteKeys);
        return observations.stream()
                .filter(f -> evidence.contains(f.getOccurrenceKey()))
                .toList();
    }

    private static Set<String> deliveredKeys(List<DeliveredSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.disposition() != Disposition.FAILED)
                .map(DeliveredSignal::deliveryKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Fire {@link PracticeDetectionDeliveredEvent} so both longitudinal lanes can route this cycle's
     * observations — IN_CHAT units for the mentor, IN_APP units for the developer's practice pages.
     * Best-effort - a publish failure must never poison the ledger write or the delivery already received.
     *
     * <p><b>Never gated on silent mode.</b> Silence stops what leaves the instance; neither lane this wakes
     * leaves it. An IN_APP unit is read on the developer's own pages and egresses nowhere, and IN_CHAT's
     * egress is refused at the turn itself, by {@code ConversationalDeliveryReconciler}. Withholding the
     * signal here silenced both of them as a side effect of silencing the merge-request note, and left the
     * hourly {@code FeedbackLanePreparationSweeper} as the only path — which then logs "the listeners are
     * dropping events" on every pass, because under silence they always were.
     */
    private void publishFeedbackLaneTrigger(AgentJob job) {
        try {
            eventPublisher.publishEvent(new PracticeDetectionDeliveredEvent(
                    job.getId(), job.getWorkspace().getId()));
        } catch (RuntimeException e) {
            log.warn("Feedback-lane trigger publish failed (delivery unaffected): jobId={}", job.getId(), e);
        }
    }

    /**
     * Close a review that composed nothing to put on the work: wake the longitudinal lanes, which do not depend
     * on a public note, and record each observation the composer explicitly withheld. Opens no approval item.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNothingToPost(AgentJob job, @Nullable DeliveryContent delivery) {
        publishFeedbackLaneTrigger(job);
        recordWithheldOnly(job, delivery);
    }

    private void recordWithheldOnly(AgentJob job, @Nullable DeliveryContent delivery) {
        if (delivery == null || delivery.withheld().isEmpty() || job.getWorkspace() == null) {
            return;
        }
        Set<UUID> alreadySuppressed =
                new HashSet<>(feedbackObservationRepository.findObservationIdsSuppressedForJob(job.getId()));
        Map<String, FeedbackSuppressionReason> withheldByKey = delivery.withheld().stream()
                .collect(Collectors.toMap(
                        PracticeDetectionResultParser.WithheldObservation::occurrenceKey,
                        PracticeDetectionResultParser.WithheldObservation::reason));
        List<Observation> withheld =
                observationRepository
                        .findByAgentJobId(job.getId(), job.getWorkspace().getId())
                        .stream()
                        .filter(f -> withheldByKey.containsKey(f.getOccurrenceKey()))
                        .filter(f -> !alreadySuppressed.contains(f.getId()))
                        .sorted(ObservationOrder.worstFirst())
                        .toList();
        recordComposerWithheld(job, withheld, withheldByKey);
    }

    /**
     * Record each never-rendered observation as a SUPPRESSED unit carrying the composer's reason, so an
     * eval excludes it rather than scoring a model-correct-but-policy-withheld observation as a miss. Runs in the
     * caller's transaction so these rows and the DELIVERED unit they qualify commit together.
     */
    private void recordComposerWithheld(
            AgentJob job, List<Observation> withheld, Map<String, FeedbackSuppressionReason> reasonByKey) {
        Instant now = Instant.now();
        int index = 0;
        for (Observation droppedObservation : withheld) {
            int unitOrdinal = COMPOSER_WITHHELD_UNIT_ORDINAL_BASE + index++;
            if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), unitOrdinal)) {
                continue;
            }
            FeedbackSuppressionReason reason = reasonByKey.get(droppedObservation.getOccurrenceKey());
            Feedback unit = feedbackRepository.save(Feedback.builder()
                    .agentJobId(job.getId())
                    .workspaceId(job.getWorkspace().getId())
                    .artifactKind(droppedObservation.getArtifactKind())
                    .artifactId(droppedObservation.getArtifactId())
                    .recipientUserId(droppedObservation.getAboutUserId())
                    .aboutUserId(droppedObservation.getAboutUserId())
                    .channel(FeedbackChannel.IN_CONTEXT)
                    .position(unitOrdinal)
                    .deliveryState(FeedbackDeliveryState.SUPPRESSED)
                    .suppressionReason(reason)
                    .source(FeedbackSource.AGENT)
                    .createdAt(now)
                    .build());
            feedbackObservationRepository.insertIfAbsent(
                    unit.getId(), droppedObservation.getId(), EvidenceRole.PRIMARY.name(), 0);
        }
        log.info("Composer-withheld: jobId={}, dropped(suppressed)={}", job.getId(), withheld.size());
    }

    /**
     * Record a whole prepared review that a delivery gate withheld as ONE SUPPRESSED {@code IN_CONTEXT} unit
     * (ordinal {@link #GATE_SUPPRESSED_UNIT_ORDINAL}) binding its assessed observations, with the composed body
     * kept for audit. Without it, a gate-withheld review reads exactly like one that was delivered and ignored.
     *
     * <p>Publishes the lane trigger when the reason concerns only the note on the work:
     * {@link FeedbackSuppressionReason#INSTANCE_SILENCED}, which stops what leaves the instance, and
     * {@link FeedbackSuppressionReason#REPEATS_DELIVERED_NOTE}, whose words are already there. The developer's
     * own pages and conversations are then prepared now rather than when the hourly sweeper next passes, each
     * under its own policy. Every other gate decision (closed PR, opted-out author) applies to every channel, so
     * those loci must not resurface anywhere. No-ops when a DELIVERED feedback already exists for the job or on
     * retry. REQUIRES_NEW, best-effort: callers wrap in try/catch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressedUnit(AgentJob job, DeliveryContent delivery, FeedbackSuppressionReason reason) {
        recordSuppressedUnitInCurrentTransaction(job, delivery, reason);
        if (reason == FeedbackSuppressionReason.INSTANCE_SILENCED
                || reason == FeedbackSuppressionReason.REPEATS_DELIVERED_NOTE) {
            publishFeedbackLaneTrigger(job);
        }
    }

    private void recordSuppressedUnitInCurrentTransaction(
            AgentJob job, DeliveryContent delivery, FeedbackSuppressionReason reason) {
        if (delivery == null || job.getWorkspace() == null) {
            return;
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), IN_CONTEXT_UNIT_ORDINAL)) {
            return; // a DELIVERED unit already exists (a prior run landed) — never contradict it
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), GATE_SUPPRESSED_UNIT_ORDINAL)) {
            return; // already recorded (job retry)
        }
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (observations.isEmpty()) {
            return;
        }
        saveSuppressedUnit(job, delivery, reason, observations, writtenFrom(observations, delivery));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressedRemainder(
            AgentJob job,
            DeliveryContent delivery,
            FeedbackSuppressionReason reason,
            List<String> suppressedDeliveryKeys) {
        if (delivery == null || job.getWorkspace() == null) {
            return;
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), GATE_SUPPRESSED_UNIT_ORDINAL)) {
            return;
        }
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (observations.isEmpty()) {
            return;
        }
        saveSuppressedUnit(
                job,
                delivery,
                reason,
                observations,
                behindNotes(observations, delivery, Set.copyOf(suppressedDeliveryKeys)));
    }

    /**
     * Record the line notes of a partially delivered package that never landed as one FAILED unit, bound to
     * their own evidence and carrying their text, beside the DELIVERED unit {@link #record} wrote for the
     * placements that did.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUndeliveredRemainder(AgentJob job, DeliveryContent delivery, List<String> undeliveredKeys) {
        if (undeliveredKeys.isEmpty() || job.getWorkspace() == null) {
            return;
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), UNDELIVERED_UNIT_ORDINAL)) {
            return; // already recorded (job retry)
        }
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (observations.isEmpty()) {
            return;
        }
        Set<String> keys = Set.copyOf(undeliveredKeys);
        Observation any = observations.get(0);
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(any.getArtifactKind())
                .artifactId(any.getArtifactId())
                .recipientUserId(any.getAboutUserId())
                .aboutUserId(any.getAboutUserId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(UNDELIVERED_UNIT_ORDINAL)
                .deliveryState(FeedbackDeliveryState.FAILED)
                .body(delivery.diffNotes().stream()
                        .filter(note -> note.deliveryKey() != null && keys.contains(note.deliveryKey()))
                        .map(DiffNote::body)
                        .collect(Collectors.joining("\n\n")))
                .source(FeedbackSource.AGENT)
                .threadKey(feedbackThreadKeyFor(any))
                .createdAt(Instant.now())
                .build());
        int ordinal = 0;
        for (Observation f : behindNotes(observations, delivery, keys).stream()
                .filter(f -> f.getAssessmentStatus() == AssessmentStatus.ASSESSED)
                .sorted(ObservationOrder.worstFirst())
                .toList()) {
            EvidenceRole role = f.getOutcome() == Outcome.NEGATIVE ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal++);
        }
    }

    private void saveSuppressedUnit(
            AgentJob job,
            DeliveryContent delivery,
            FeedbackSuppressionReason reason,
            List<Observation> observations,
            List<Observation> evidence) {
        Observation any = observations.get(0);
        String feedbackThreadKey = feedbackThreadKeyFor(any);
        UUID replacesId = feedbackPlacementRepository
                .findLatestDeliveredSummary(feedbackThreadKey)
                .map(FeedbackPlacement::getFeedbackId)
                .orElse(null);
        Instant now = Instant.now();
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(any.getArtifactKind())
                .artifactId(any.getArtifactId())
                .recipientUserId(any.getAboutUserId())
                .aboutUserId(any.getAboutUserId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(GATE_SUPPRESSED_UNIT_ORDINAL)
                .deliveryState(FeedbackDeliveryState.SUPPRESSED)
                .suppressionReason(reason)
                .body(delivery.mrNote())
                .source(FeedbackSource.AGENT)
                .threadKey(feedbackThreadKey)
                .replacesId(replacesId)
                .createdAt(now)
                .build());
        int ordinal = 0;
        List<Observation> assessed = evidence.stream()
                .filter(f -> (f.getAssessmentStatus() == AssessmentStatus.ASSESSED))
                .sorted(ObservationOrder.worstFirst())
                .toList();
        for (Observation f : assessed) {
            EvidenceRole role = f.getOutcome() == Outcome.NEGATIVE ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal++);
        }
        log.info(
                "Feedback suppressed (delivery gate): jobId={}, unit={}, reason={}, boundObservations={}",
                job.getId(),
                feedback.getId(),
                reason,
                assessed.size());
    }

    /**
     * Record a SUPPRESSED ledger unit for a locus withheld by reaction-aware suppression (ADR 0021) — the
     * student already DISPUTED / marked NOT_APPLICABLE / DISMISSED this concern, so it was NOT re-delivered.
     * Writing it (rather than silently dropping) means an eval sees the observation was deliberately withheld, not
     * a model miss. Uses a high {@code unit_ordinal} ({@value #SUPPRESSED_UNIT_ORDINAL_BASE}+) so it never
     * collides with the live IN_CONTEXT unit (ordinal 0) on the {@code (agent_job_id, unit_ordinal)} guard.
     * Best-effort: REQUIRES_NEW, callers wrap in try/catch — a ledger failure never affects delivery.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressed(AgentJob job, Observation observation, FeedbackSuppressionReason reason, int index) {
        recordSuppressedAt(job, observation, reason, SUPPRESSED_UNIT_ORDINAL_BASE + index);
    }

    /**
     * Record a SUPPRESSED {@code IN_CONTEXT} unit for a locus that was measured and recorded but not let
     * through to the artifact — deliberately unsaid. Sits in its own ordinal band
     * ({@value #AUTONOMY_WITHHELD_UNIT_ORDINAL_BASE}+) so it never collides with the reaction-aware band.
     * Best-effort like its sibling: REQUIRES_NEW, callers wrap in try/catch.
     *
     * @param reason which of the two withholding rules fired — the practice's autonomy, or the
     *     observation's backfill provenance. Passed in rather than fixed because the two are undone by
     *     different acts and an evaluation has to be able to tell them apart.
     * @param index position within the band; the caller must keep it under
     *     {@link #UNIT_ORDINAL_BAND_WIDTH} so the band cannot overflow into the next one
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordWithheld(AgentJob job, Observation observation, FeedbackSuppressionReason reason, int index) {
        recordSuppressedAt(job, observation, reason, AUTONOMY_WITHHELD_UNIT_ORDINAL_BASE + index);
    }

    /** Stores the exact separately composed human-approval body before any provider side effect. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProposal(AgentJob job, @Nullable DeliveryContent delivery) {
        publishFeedbackLaneTrigger(job);
        final int position = APPROVAL_UNIT_ORDINAL;
        if (delivery == null || delivery.mrNote() == null) {
            recordWithheldOnly(job, delivery);
            return;
        }
        String body = PullRequestCommentPoster.sanitize(delivery.mrNote());
        if (body.isBlank()) return;
        String providerSummary = commentFormatter.appendDisclosure(body, job);
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), position)) return;
        List<Observation> proposed = writtenFrom(
                observationRepository.findByAgentJobId(
                        job.getId(), job.getWorkspace().getId()),
                delivery);
        if (proposed.isEmpty()) return;
        Observation first = proposed.get(0);
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(first.getArtifactKind())
                .artifactId(first.getArtifactId())
                .recipientUserId(first.getAboutUserId())
                .aboutUserId(first.getAboutUserId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(position)
                .deliveryState(FeedbackDeliveryState.AWAITING_APPROVAL)
                .body(providerSummary)
                .proposedPlacements(proposedPlacements(delivery, providerSummary))
                .reviewedRevision(reviewedRevision(job))
                .proposedPracticeSlugs(proposed.stream()
                        .map(observation -> observation.getPractice().getSlug())
                        .distinct()
                        .sorted()
                        .toList())
                .source(FeedbackSource.AGENT)
                .threadKey(feedbackThreadKeyFor(first))
                .createdAt(Instant.now())
                .build());
        feedbackRepository.supersedeUndecidedProposals(
                job.getWorkspace().getId(), feedbackThreadKeyFor(first), feedback.getId());
        int ordinal = 0;
        for (Observation observation : proposed) {
            feedbackObservationRepository.insertIfAbsent(
                    feedback.getId(), observation.getId(), EvidenceRole.PRIMARY.name(), ordinal++);
        }
        recordWithheldOnly(job, delivery);
    }

    /** The observations the content was written from, in its order; all of them for a pre-upgrade package. */
    private static List<Observation> writtenFrom(List<Observation> observations, DeliveryContent delivery) {
        List<String> contributors = delivery.contributors();
        if (contributors == null) return observations;
        return observations.stream()
                .filter(observation -> contributors.contains(observation.getOccurrenceKey()))
                .sorted(java.util.Comparator.comparingInt(
                        observation -> contributors.indexOf(observation.getOccurrenceKey())))
                .toList();
    }

    private List<ProposedPlacement> proposedPlacements(DeliveryContent delivery, String summary) {
        var placements =
                new java.util.ArrayList<ProposedPlacement>(delivery.diffNotes().size() + 1);
        placements.add(ProposedPlacement.summary(summary));
        for (DiffNote note : delivery.diffNotes()) {
            String body = PullRequestCommentPoster.sanitize(note.body());
            if (!body.isBlank()) {
                placements.add(ProposedPlacement.inline(
                        commentFormatter.appendInlineFeedbackPrompt(body),
                        note.filePath(),
                        note.startLine(),
                        note.endLine(),
                        note.deliveryKey()));
            }
        }
        return List.copyOf(placements);
    }

    private static @Nullable String reviewedRevision(AgentJob job) {
        if (job.getMetadata() == null) return null;
        String revision = job.getMetadata().path("commit_sha").asString();
        return revision == null || revision.isBlank() ? null : revision;
    }

    private void recordSuppressedAt(
            AgentJob job, Observation observation, FeedbackSuppressionReason reason, int unitOrdinal) {
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), unitOrdinal)) {
            return; // already recorded (job retry)
        }
        Instant now = Instant.now();
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(observation.getArtifactKind())
                .artifactId(observation.getArtifactId())
                .recipientUserId(observation.getAboutUserId())
                .aboutUserId(observation.getAboutUserId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(unitOrdinal)
                .deliveryState(FeedbackDeliveryState.SUPPRESSED)
                .suppressionReason(reason)
                .source(FeedbackSource.AGENT)
                .threadKey(feedbackThreadKeyFor(observation))
                .createdAt(now)
                .build());
        feedbackObservationRepository.insertIfAbsent(
                feedback.getId(), observation.getId(), EvidenceRole.PRIMARY.name(), 0);
        log.info(
                "Feedback suppressed: jobId={}, unit={}, reason={}, recurrenceKey={}",
                job.getId(),
                feedback.getId(),
                reason,
                observation.getRecurrenceKey());
    }

    /**
     * Persist the composed body a delivery attempt could not place as a single {@link FeedbackDeliveryState#FAILED}
     * {@code IN_CONTEXT} unit (ordinal {@link #UNDELIVERED_UNIT_ORDINAL}), bind the assessed observations it was written from
     * (BAD=PRIMARY, GOOD=SUPPORTING), and signal the conversational channel to cover the loci the developer never
     * saw in-context. No-ops entirely when there is no body/workspace or a DELIVERED unit already exists (a prior
     * run landed); otherwise signals the conversation, then writes the FAILED row unless it already exists (a
     * retry re-signals harmlessly but never double-persists) or the job has no observations. REQUIRES_NEW,
     * best-effort: callers wrap in try/catch. The FAILED row feeds only the operator surfaces; the mentor
     * reads DELIVERED-only, so it never feeds coaching.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordUndelivered(AgentJob job, @Nullable DeliveryContent delivery) {
        if (job.getWorkspace() == null) {
            return; // a no-workspace integrity failure has no recipient/artifact to bind
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), IN_CONTEXT_UNIT_ORDINAL)) {
            return; // a DELIVERED unit already exists (a prior run landed) — record() signalled, do not re-signal
        }
        // Signalled here, above the note check and above silent mode, because the lanes this wakes are
        // internal and neither condition bears on them. A review that composed nothing to post on the work
        // can still have composed a message about the way of working behind it, so gating this on `mrNote` made the
        // developer's private page a passenger of the public comment — the same mistake as gating it on
        // silence, one level up. An in-context note is one lane's output, not a precondition for the others.
        publishFeedbackLaneTrigger(job);
        if (delivery == null || delivery.mrNote() == null) {
            return; // nothing to post on the work; the lanes above are already awake
        }
        if (!deliveryAllowed()) {
            recordSuppressedUnitInCurrentTransaction(job, delivery, FeedbackSuppressionReason.INSTANCE_SILENCED);
            return;
        }
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), UNDELIVERED_UNIT_ORDINAL)) {
            return; // already recorded (job retry)
        }
        List<Observation> observations = observationRepository.findByAgentJobId(
                job.getId(), job.getWorkspace().getId());
        if (observations.isEmpty()) {
            return;
        }
        Observation any = observations.get(0);
        Instant now = Instant.now();
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(any.getArtifactKind())
                .artifactId(any.getArtifactId())
                .recipientUserId(any.getAboutUserId())
                .aboutUserId(any.getAboutUserId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(UNDELIVERED_UNIT_ORDINAL)
                .deliveryState(FeedbackDeliveryState.FAILED)
                .body(delivery.mrNote())
                .source(FeedbackSource.AGENT)
                .threadKey(feedbackThreadKeyFor(any))
                .createdAt(now)
                .build());
        int ordinal = 0;
        List<Observation> assessed = writtenFrom(observations, delivery).stream()
                .filter(f -> (f.getAssessmentStatus() == AssessmentStatus.ASSESSED))
                .sorted(ObservationOrder.worstFirst())
                .toList();
        for (Observation f : assessed) {
            EvidenceRole role = f.getOutcome() == Outcome.NEGATIVE ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal++);
        }
        log.info(
                "Feedback recorded as undelivered (FAILED): jobId={}, unit={}, boundObservations={}",
                job.getId(),
                feedback.getId(),
                assessed.size());
    }

    /** Match a placement only by its exact delivery identity. Shared coordinates do not establish identity. */
    private static @Nullable DeliveredSignal matchSignal(DiffNote note, List<DeliveredSignal> signals) {
        if (note.deliveryKey() == null) return null;
        return signals.stream()
                .filter(signal -> note.deliveryKey().equals(signal.deliveryKey()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The stable continuity line for an observation: (target, recipient, in-context surface).
     *
     * <p>The recipient arg is intentionally {@code getAboutUserId()}: recipient == about for the author-side
     * catalogue. For reviewer-audience practices (recipient != about), this MUST switch to the
     * recipient id, or supersession continuity would key off the subject and mis-thread —
     * {@link FeedbackThreadKey#compute} documents that arg as the user the unit is delivered to.
     */
    private static String feedbackThreadKeyFor(Observation any) {
        return FeedbackThreadKey.compute(
                any.getArtifactKind().value(), any.getArtifactId(), any.getAboutUserId(), FeedbackChannel.IN_CONTEXT);
    }

    private boolean deliveryAllowed() {
        return egressGuard.deliveryAllowed("prepare-conversational-feedback");
    }
}
