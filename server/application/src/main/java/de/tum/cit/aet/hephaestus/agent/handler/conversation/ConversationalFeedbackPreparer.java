package de.tum.cit.aet.hephaestus.agent.handler.conversation;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.agent.handler.ObservationOrder;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationBriefBody;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * Prepares bounded {@code IN_CHAT} units for each observation subject. Only complete composed briefs are
 * queued: observations are evidence for a mentor move, not a substitute for one.
 *
 * <p>A practice-scoped thread key keeps one unread unit per practice. Supersession and replacement run in the
 * same transaction, while deterministic job positions make retries idempotent.
 */
@Component
public class ConversationalFeedbackPreparer {

    private static final Logger log = LoggerFactory.getLogger(ConversationalFeedbackPreparer.class);

    /** Cap on prepared conversational units per recipient per cycle - bounds the mentor's "raise next" queue. */
    static final int TOP_N_PER_RECIPIENT = 3;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final ObservationRepository observationRepository;
    private final FeedbackSupersession supersession;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboundEgressGuard egressGuard;

    public ConversationalFeedbackPreparer(
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            ObservationRepository observationRepository,
            FeedbackSupersession supersession,
            ApplicationEventPublisher eventPublisher,
            OutboundEgressGuard egressGuard) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.observationRepository = observationRepository;
        this.supersession = supersession;
        this.eventPublisher = eventPublisher;
        this.egressGuard = egressGuard;
    }

    /**
     * Prepare PREPARED IN_CHAT units for {@code admitted} observations of a job. Runs REQUIRES_NEW so a
     * preparation failure is isolated; idempotent on a re-run via the {@code (agent_job_id, position)} guard.
     *
     * @param composed this job's composed units for this lane, in the order the stage reported them; empty when
     *     the composition stage did not run, failed, or wrote nothing for the conversation channel
     * @return the number of units newly prepared this call (0 on a pure re-run)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int prepare(
            UUID agentJobId, Long workspaceId, List<Observation> admitted, List<ComposedFeedbackUnit> composed) {
        if (admitted.isEmpty()) {
            return 0;
        }
        if (!egressGuard.deliveryAllowed("prepare-conversational-feedback")) {
            return 0;
        }
        Composition composition = Composition.of(composed);
        if (!composition.spoke()) {
            // A missing/failed composition stage is not permission to manufacture a queue from severity.
            // The mentor needs a prepared coaching brief, and a null body cannot distinguish deliberate
            // silence from infrastructure failure. The lane recovery marker is still written by the caller.
            return 0;
        }
        List<Observation> ordered = admitted.stream()
                .sorted(Comparator.comparingLong(Observation::getAboutUserId)
                        .thenComparing(ObservationOrder.worstFirst()))
                .collect(Collectors.toList());

        // Every admitted observation consumes a slot, so the band is not bounded by the per-recipient cap.
        // Overflowing it would collide with the next band and silently drop the very rows this records —
        // fail loud instead; a job with this many admitted loci is pathological.
        if (ordered.size() > FeedbackLedgerRecorder.UNIT_ORDINAL_BAND_WIDTH) {
            throw new IllegalStateException("Conversational units exceed the ordinal band: jobId=" + agentJobId
                    + ", admitted="
                    + ordered.size()
                    + ", band="
                    + FeedbackLedgerRecorder.UNIT_ORDINAL_BAND_WIDTH);
        }

        // Read once, from a projection, and needed on every path: the practice is both the join between what
        // the composer wrote and what was measured, and the scope of the row's continuity key. The
        // observations arrive from whichever caller routed them and may be detached, so walking
        // o.practice.slug would make a row supersedable — or a composed message deliverable — depending on
        // whether that caller happened to hold a session.
        Map<UUID, String> practiceSlugs = practiceSlugsOf(ordered, workspaceId);
        Map<Long, Integer> perRecipientCount = new HashMap<>();
        // Newly CREATED units only (re-run no-ops excluded) — feeds the per-recipient prepared event.
        Map<Long, Integer> newlyPreparedByRecipient = new HashMap<>();
        // One message per practice per recipient: several loci of the same practice are one thing to raise,
        // and the composer already named the others in prose inside the move it wrote.
        Set<String> raisedPractices = new HashSet<>();
        Set<String> matchedUnits = new HashSet<>();
        Instant now = Instant.now();
        int position = FeedbackLedgerRecorder.IN_CHAT_UNIT_ORDINAL_BASE;
        int prepared = 0;
        int superseded = 0;
        for (Observation observation : ordered) {
            long recipient = observation.getAboutUserId();
            String practiceSlug = practiceSlugs.get(observation.getId());
            // Advanced for every admitted locus, raised or not, so the grain a re-run recognises depends on
            // the measurements alone and not on what the composer happened to say about them.
            int unitPosition = position++;

            String body = null;
            ComposedFeedbackUnit move = null;
            if (composition.spoke()) {
                ComposedFeedbackUnit unit = practiceSlug == null ? null : composition.raisable(practiceSlug);
                if (unit == null) {
                    // Either the composer withheld this practice, or it read the locus and wrote nothing.
                    // Both are the evidence's reason rather than ours, and a refusal that belongs to the
                    // evidence is not a withholding to explain — the reason itself stays on the job's output.
                    log.debug(
                            "Conversational locus not raised, composer wrote nothing for it: jobId={}, practice={}",
                            agentJobId,
                            practiceSlug);
                    continue;
                }
                if (!raisedPractices.add(recipient + "|" + practiceSlug)) {
                    continue;
                }
                matchedUnits.add(practiceSlug);
                move = unit;
                ComposedFeedbackUnit.ConversationBrief brief = Objects.requireNonNull(unit.notes());
                body = ConversationBriefBody.render(
                        Objects.requireNonNull(unit.title()),
                        brief.situation(),
                        brief.capability(),
                        brief.evidenceSummary(),
                        brief.inConversationSignal(),
                        brief.alreadySaid());
            }

            int count = perRecipientCount.getOrDefault(recipient, 0);
            // Over the per-recipient cap the locus is withheld, not raised. The cap is ours, not the
            // evidence's, and the router already established nobody has seen it, so it still gets a row —
            // dropping it silently would leave it bound to a DELIVERED unit and read as feedback the
            // developer ignored.
            boolean overCap = count >= TOP_N_PER_RECIPIENT;
            if (!overCap) {
                perRecipientCount.put(recipient, count + 1);
            }
            if (feedbackRepository.existsByAgentJobIdAndPosition(agentJobId, unitPosition)) {
                // A re-run reaching a unit it already wrote must not supersede a second time: the move it
                // would retire is the one this very unit replaced on the first pass.
                continue;
            }
            // The practice this row continues. Scoped to the practice rather than to the locus, because the
            // mentor raises a way of working and not a line; a capped row gets none, because it is never raised and
            // putting it at the head of the thread would leave the queued move behind it unreplaceable.
            String threadKey = overCap || practiceSlug == null
                    ? null
                    : FeedbackThreadKey.forPractice(practiceSlug, recipient, FeedbackChannel.IN_CHAT);
            // The claim and the write below are one swap, and this method's REQUIRES_NEW transaction is what
            // makes them one: a retired move with no replacement leaves the mentor with nothing to raise
            // about a practice it was about to raise.
            FeedbackSupersession.Outcome outcome = threadKey != null && supersedes(move, threadKey)
                    ? supersession.supersede(workspaceId, recipient, FeedbackChannel.IN_CHAT, threadKey)
                    : FeedbackSupersession.Outcome.standalone();
            if (outcome.retiredSomething()) {
                superseded++;
            }
            Feedback unit = feedbackRepository.save(Feedback.builder()
                    .agentJobId(agentJobId)
                    .workspaceId(workspaceId)
                    .artifactKind(observation.getArtifactKind())
                    .artifactId(observation.getArtifactId())
                    .recipientUserId(recipient)
                    .aboutUserId(recipient)
                    .channel(FeedbackChannel.IN_CHAT)
                    .position(unitPosition)
                    .deliveryState(overCap ? FeedbackDeliveryState.SUPPRESSED : FeedbackDeliveryState.PREPARED)
                    .suppressionReason(overCap ? FeedbackSuppressionReason.VOLUME_CAPPED : null)
                    // A capped locus is never raised, so freezing a move on it would store a coaching plan
                    // nothing can ever read back.
                    .body(overCap ? null : body)
                    .source(FeedbackSource.AGENT)
                    // Carried on every raised row, not only on a replacement: this is the handle the composer
                    // is shown to name a supersession target, and a row written without one can never be
                    // replaced by anything.
                    .threadKey(threadKey)
                    // What this move follows, whether or not it managed to retire it: a move that arrived
                    // after the mentor had already raised its predecessor still continues that thread.
                    .replacesId(outcome.replacesId())
                    .createdAt(now)
                    .build());
            feedbackObservationRepository.insertIfAbsent(
                    unit.getId(), observation.getId(), EvidenceRole.PRIMARY.name(), 0);
            if (!overCap) {
                newlyPreparedByRecipient.merge(recipient, 1, Integer::sum);
                prepared++;
            }
        }
        if (prepared > 0) {
            log.info(
                    "Conversational feedback prepared: jobId={}, units={}, superseded={}, composed={}",
                    agentJobId,
                    prepared,
                    superseded,
                    composition.spoke());
        }
        // A move about a practice this run measured nothing admissible for cannot be prepared: the queue is
        // read through the observations bound to a unit, so an evidence-free unit would be invisible to the
        // mentor and unexplainable afterwards.
        composition.raisableSlugs().stream()
                .filter(slug -> !matchedUnits.contains(slug))
                .forEach(slug -> log.info(
                        "Composed conversational move dropped, no admitted locus for its practice: jobId={}, practice={}",
                        agentJobId,
                        slug));
        // Published inside this REQUIRES_NEW transaction so AFTER_COMMIT listeners (the Slack nudge) fire
        // exactly when the units are durably visible — and not at all on a pure re-run.
        newlyPreparedByRecipient.forEach((recipient, count) ->
                eventPublisher.publishEvent(new ConversationFeedbackPreparedEvent(workspaceId, recipient, count)));
        return prepared;
    }

    /**
     * This lane's share of one composition turn, indexed by the practice each unit names.
     *
     * <p>{@link #spoke()} is true when the stage emitted anything at all for this lane, including a pure
     * WITHHOLD — a composer that decided to stay quiet has decided, and falling back to a severity ranking
     * would overrule it with the very mechanism the second phase exists to replace.
     */
    private record Composition(boolean spoke, Map<String, ComposedFeedbackUnit> raisableByPractice) {
        static Composition of(List<ComposedFeedbackUnit> composed) {
            Map<String, ComposedFeedbackUnit> raisable = new LinkedHashMap<>();
            for (ComposedFeedbackUnit unit : composed) {
                if (unit.channel() == FeedbackChannel.IN_CHAT
                        && unit.action() != ComposedFeedbackUnit.Action.WITHHOLD
                        && unit.isComplete()
                        && unit.notes() != null
                        && unit.title() != null) {
                    // The parser already admits one unit per practice per channel; first wins either way.
                    raisable.putIfAbsent(normalizeSlug(unit.practiceSlug()), unit);
                }
            }
            return new Composition(!composed.isEmpty(), Map.copyOf(raisable));
        }

        @Nullable
        ComposedFeedbackUnit raisable(String practiceSlug) {
            return raisableByPractice.get(practiceSlug);
        }

        Set<String> raisableSlugs() {
            return raisableByPractice.keySet();
        }
    }

    /**
     * Whether this move may retire the one queued on the thread it named.
     *
     * <p>A move is only ever allowed to replace a move <em>about the same practice</em>. The runner already
     * refuses a key that was never staged, so the composer cannot invent one; what it can still do is name a
     * real key belonging to another of this person's practices, and acting on that would retire a message about
     * something else and leave it unsaid forever. The check is an equality because the key is derived from
     * the practice: the only key this move could legitimately name is its own thread's.
     */
    private static boolean supersedes(@Nullable ComposedFeedbackUnit move, String ownThreadKey) {
        if (move == null || move.action() != ComposedFeedbackUnit.Action.SUPERSEDE) {
            return false;
        }
        if (ownThreadKey.equals(move.supersedesThreadKey())) {
            return true;
        }
        log.warn(
                "Conversational move named a supersession target on another practice's thread; written as new: practice={}",
                move.practiceSlug());
        return false;
    }

    /**
     * The practice each locus is about, in the composer's spelling. The composer names a practice and nothing
     * else about the evidence, so this is the whole of the join between what it wrote and what was measured —
     * and, because a practice thread is scoped to the practice, the same spelling is what the continuity key is
     * derived from, on this lane and on the in-app lane alike.
     */
    private Map<UUID, String> practiceSlugsOf(List<Observation> observations, Long workspaceId) {
        List<UUID> ids = observations.stream()
                .map(Observation::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> slugs = new HashMap<>(ids.size());
        for (ObservationRepository.ObservationPracticeSlug row :
                observationRepository.practiceSlugsFor(ids, workspaceId)) {
            String slug = row.getPracticeSlug();
            if (slug != null && !slug.isBlank()) {
                slugs.put(row.getObservationId(), normalizeSlug(slug));
            }
        }
        return slugs;
    }

    private static String normalizeSlug(String practiceSlug) {
        return practiceSlug.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
