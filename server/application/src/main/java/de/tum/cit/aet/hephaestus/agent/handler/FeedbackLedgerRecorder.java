package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
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
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
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
            if (signal.disposition() == Disposition.FAILED || signal.externalRef() == null) continue;
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
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), IN_CONTEXT_UNIT_ORDINAL)) {
            return; // already recorded (job retry)
        }

        Observation any = observations.get(0);
        long recipientUserId = any.getAboutUserId();
        ArtifactKind artifactKind = any.getArtifactKind();
        Long artifactId = any.getArtifactId();
        String feedbackThreadKey = feedbackThreadKeyFor(any);

        UUID supersedesId = summaryDelivered
                ? feedbackPlacementRepository
                        .findLatestDeliveredSummary(feedbackThreadKey)
                        .map(FeedbackPlacement::getFeedbackId)
                        .orElse(null)
                : null;

        Instant now = Instant.now();
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(job.getWorkspace().getId())
                .artifactKind(artifactKind)
                .artifactId(artifactId)
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
            feedbackRepository.updateState(supersedesId, FeedbackDeliveryState.SUPERSEDED.name());
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

        // Bind every DELIVERED observation: BAD (the problems surfaced) lead as PRIMARY, GOOD
        // strengths as SUPPORTING; observations that carry no valence and withheld observations are excluded —
        // feedback is an intervention, and there is nothing in either to intervene about.
        // Severity is null for a GOOD strength (ADR 0022) — sort it after any problem (least severe).
        Set<String> deliveredInlineKeys = deliveredKeys(inlineSignals);
        List<Observation> assessed = observations.stream()
                .filter(f -> (f.getAssessmentStatus() == AssessmentStatus.ASSESSED))
                .filter(f -> !excludedIds.contains(f.getId()))
                .filter(f -> summaryDelivered || deliveredInlineKeys.contains(f.getRecurrenceKey()))
                // Stable order matching the composer's prioritisation, and the same ObservationOrder it uses:
                // severity, then how much of the work the observation's citations span, then id — so the persisted
                // PRIMARY ordinal of equal-severity problems is reproducible across re-runs rather than flapping
                // with the repository's findByAgentJobId iteration order.
                .sorted(ObservationOrder.worstFirst())
                .toList();
        int ordinal = 0;
        for (Observation f : assessed) {
            EvidenceRole role = f.getAssessment() == Assessment.BAD ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal++);
        }

        if (summaryExternalRef != null) {
            feedbackPlacementRepository.save(FeedbackPlacement.builder()
                    .feedback(feedback)
                    .placementType(PlacementType.SUMMARY)
                    .postedCommentRef(summaryExternalRef)
                    .createdAt(now)
                    .build());
        }

        int inlinePlacementCount = 0;
        if (ArtifactKinds.hasInlineLane(artifact) && inlineDelivered) {
            for (DiffNote note : delivery.diffNotes()) {
                DeliveredSignal signal = matchSignal(note, inlineSignals);
                if (signal == null || signal.disposition() == Disposition.FAILED) {
                    continue;
                }
                feedbackPlacementRepository.save(FeedbackPlacement.builder()
                        .feedback(feedback)
                        .placementType(PlacementType.INLINE)
                        .anchorKind(note.endLine() != null ? PlacementAnchorKind.RANGE : PlacementAnchorKind.LINE)
                        .anchorPath(note.filePath())
                        .anchorStartLine(note.startLine())
                        .anchorEndLine(note.endLine())
                        .anchorSide(PlacementAnchorSide.NEW)
                        .postedCommentRef(signal.externalRef())
                        .createdAt(now)
                        .build());
                inlinePlacementCount++;
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
                feedbackThreadKey);
    }

    private static Set<String> deliveredKeys(List<DeliveredSignal> signals) {
        return signals.stream()
                .filter(signal -> signal.disposition() != Disposition.FAILED)
                .map(DeliveredSignal::recurrenceKey)
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
     * <p>Publishes NO conversational trigger: a gate decision (closed PR, opted-out author) applies to every
     * channel, so the loci must not resurface in a mentor turn. No-ops when a DELIVERED unit already exists for
     * the job or on retry. REQUIRES_NEW, best-effort: callers wrap in try/catch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressedUnit(AgentJob job, DeliveryContent delivery, FeedbackSuppressionReason reason) {
        recordSuppressedUnitInCurrentTransaction(job, delivery, reason);
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
        saveSuppressedUnit(job, delivery, reason, observations, observations);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuppressedRemainder(
            AgentJob job,
            DeliveryContent delivery,
            FeedbackSuppressionReason reason,
            List<String> suppressedRecurrenceKeys) {
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
        Set<String> suppressedKeys = Set.copyOf(suppressedRecurrenceKeys);
        List<Observation> suppressedObservations = observations.stream()
                .filter(f -> suppressedKeys.contains(f.getRecurrenceKey()))
                .toList();
        saveSuppressedUnit(job, delivery, reason, observations, suppressedObservations);
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
            EvidenceRole role = f.getAssessment() == Assessment.BAD ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
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
    public void recordProposal(AgentJob job, @Nullable DeliveryContent delivery, List<ValidatedObservation> proposed) {
        publishFeedbackLaneTrigger(job);
        final int position = APPROVAL_UNIT_ORDINAL;
        if (delivery == null || delivery.mrNote() == null) return;
        String body = PullRequestCommentPoster.sanitize(delivery.mrNote());
        if (body.isBlank()) return;
        String providerSummary = commentFormatter.appendDisclosure(body, job);
        if (feedbackRepository.existsByAgentJobIdAndPosition(job.getId(), position)) return;
        Map<String, Observation> stored =
                observationRepository
                        .findByAgentJobId(job.getId(), job.getWorkspace().getId())
                        .stream()
                        .filter(observation -> observation.getOccurrenceKey() != null)
                        .collect(java.util.stream.Collectors.toMap(
                                Observation::getOccurrenceKey,
                                observation -> observation,
                                (first, duplicate) -> first));
        Observation first = proposed.stream()
                .map(ValidatedObservation::occurrenceKey)
                .map(stored::get)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (first == null) return;
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
                        .map(ValidatedObservation::practiceSlug)
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
        for (ValidatedObservation candidate : proposed) {
            Observation observation = stored.get(candidate.occurrenceKey());
            if (observation != null) {
                feedbackObservationRepository.insertIfAbsent(
                        feedback.getId(), observation.getId(), EvidenceRole.PRIMARY.name(), ordinal++);
            }
        }
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
                        note.recurrenceKey()));
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
     * {@code IN_CONTEXT} unit (ordinal {@link #UNDELIVERED_UNIT_ORDINAL}), bind its assessed observations
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
        // can still have composed a message about the habit behind it, so gating this on `mrNote` made the
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
        // Bind the assessed observations (valence-carrying only) so the undelivered body traces back to its
        // observations.
        int ordinal = 0;
        List<Observation> assessed = observations.stream()
                .filter(f -> (f.getAssessmentStatus() == AssessmentStatus.ASSESSED))
                .sorted(ObservationOrder.worstFirst())
                .toList();
        for (Observation f : assessed) {
            EvidenceRole role = f.getAssessment() == Assessment.BAD ? EvidenceRole.PRIMARY : EvidenceRole.SUPPORTING;
            feedbackObservationRepository.insertIfAbsent(feedback.getId(), f.getId(), role.name(), ordinal++);
        }
        log.info(
                "Feedback recorded as undelivered (FAILED): jobId={}, unit={}, boundObservations={}",
                job.getId(),
                feedback.getId(),
                assessed.size());
    }

    /**
     * Find the delivery signal for a posted note. Primary match is the stable {@code recurrenceKey} (the
     * cross-run identity); when it is absent on either side (legacy / unkeyed notes) we fall back to the diff
     * coordinates the signal anchored at — path + the note's terminal line, which for a single-line note is its
     * start and for a range its end. Returns {@code null} when nothing matches (no signal was emitted).
     */
    private static @Nullable DeliveredSignal matchSignal(DiffNote note, List<DeliveredSignal> signals) {
        if (signals.isEmpty()) {
            return null;
        }
        if (note.recurrenceKey() != null) {
            for (DeliveredSignal s : signals) {
                if (note.recurrenceKey().equals(s.recurrenceKey())) {
                    return s;
                }
            }
        }
        int terminalLine = note.endLine() != null ? note.endLine() : note.startLine();
        for (DeliveredSignal s : signals) {
            if (s.anchor() instanceof DiffAnchor anchor
                    && note.filePath().equals(anchor.filePath())
                    && anchor.newLineNumber() == terminalLine) {
                return s;
            }
        }
        return null;
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
