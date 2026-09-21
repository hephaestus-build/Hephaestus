package de.tum.cit.aet.hephaestus.agent.handler.inapp;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackLedgerRecorder;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the IN_APP feedback units for one cycle's admitted messages.
 *
 * <p>{@code PREPARED} on write and {@code DELIVERED} on the recipient's first read (the compare-and-set
 * in {@code FeedbackRepository#markInAppDelivered}). We own this surface, so "delivered" can be an
 * observation instead of an assumption.
 *
 * <p>One open card per habit, not a pile. A newer card about a habit replaces the card still open about
 * it, queued or already read: the page is a list of habits to work on, and the newer card is the live
 * statement of this one. A card that is closed — resolved by the work or the developer, or closed because
 * the practice changed — is left as the record and the new card is written beside it. Which it is was
 * decided by whoever routed the message, from the same read the page makes
 * ({@code PreviousInAppFeedback}); the swap itself is
 * {@link de.tum.cit.aet.hephaestus.agent.handler.FeedbackSupersession#replaceOpen} and happens inside this
 * method's transaction so a retirement can never outlive its replacement.
 *
 * <p>No {@code OutboundEgressGuard} check, deliberately, and unlike every other preparer here: that
 * guard is the last gate before an <em>external</em> write, and Silent Mode means Hephaestus stops
 * talking to third parties. The practice pages are our own surface and the recipient is the subject, so
 * gating it would turn a "do not post anywhere" switch into "stop recording what we found", which is a
 * different and much larger promise.
 */
@Component
public class InAppFeedbackPreparer {

    private static final Logger log = LoggerFactory.getLogger(InAppFeedbackPreparer.class);

    /**
     * Cap on in-app units per recipient per cycle. Two, not the chat lane's three: a
     * process-level message asks the developer to change a habit, and being handed three habits at once
     * is how none of them get changed.
     *
     * <p>The composer is told the same number
     * ({@link de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs}) and its tool refuses
     * a call past it, so in a normal run nothing arrives here to cap. The bound is kept as the last one
     * standing: a runaway turn must not be able to fill a recipient's page.
     */
    public static final int TOP_N_PER_RECIPIENT = 2;

    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackSupersession supersession;

    public InAppFeedbackPreparer(
            FeedbackRepository feedbackRepository,
            FeedbackObservationRepository feedbackObservationRepository,
            FeedbackSupersession supersession) {
        this.feedbackRepository = feedbackRepository;
        this.feedbackObservationRepository = feedbackObservationRepository;
        this.supersession = supersession;
    }

    /**
     * One routed message and the evidence the router weighed, ready to be written.
     *
     * @param decision {@link InAppRoutingDecision#ADMIT} for a message the recipient will see; any
     *     other value is skipped without a row — a refusal that is a property of the evidence is not a
     *     withholding to explain, it is a message that was never owed.
     * @param replaces the card about this habit still open on the recipient's page, which this message
     *     retires; {@code null} when there is none, or when the previous card is closed and stays as the record
     */
    public record RoutedMessage(
            ComposedInAppMessage message,
            InAppRoutingDecision decision,
            List<Observation> evidence,
            @Nullable UUID replaces) {}

    /**
     * Prepare IN_APP units for one recipient in one cycle. Runs REQUIRES_NEW so a preparation failure is
     * isolated from the delivery that already happened; idempotent on a re-run through the
     * {@code (agent_job_id, position)} guard.
     *
     * @param agentJobId  the review job whose run composed these messages; the units share its id, which
     *     is why they take a band of their own rather than starting at position 0
     * @param routed      the cycle's messages for this recipient, in the order they should be offered
     * @param positionBase the first ordinal this recipient's slice of the band may use. Passed in rather
     *     than derived here because {@code (agent_job_id, position)} is unique and one job can file
     *     observations against several people: a base fixed at {@link FeedbackLedgerRecorder#IN_APP_UNIT_ORDINAL_BASE}
     *     would make the second recipient's first unit collide with the first recipient's, and the
     *     idempotency guard would read that collision as "already written" and drop the row silently.
     * @return the number of units newly prepared this call (0 on a pure re-run)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int prepare(
            UUID agentJobId, Long workspaceId, Long recipientUserId, List<RoutedMessage> routed, int positionBase) {
        if (routed.isEmpty()) {
            return 0;
        }
        int lastOrdinal = positionBase + routed.size() - 1;
        if (lastOrdinal
                >= FeedbackLedgerRecorder.IN_APP_UNIT_ORDINAL_BASE + FeedbackLedgerRecorder.UNIT_ORDINAL_BAND_WIDTH) {
            // Overflowing the band would address the next band's rows and silently drop these writes.
            // A cycle with this many composed messages, or this many recipients, is pathological — fail
            // loudly rather than write into somebody else's ordinals.
            throw new IllegalStateException("In-app units exceed the ordinal band: jobId=" + agentJobId
                    + ", lastOrdinal="
                    + lastOrdinal
                    + ", band="
                    + FeedbackLedgerRecorder.UNIT_ORDINAL_BAND_WIDTH);
        }
        Instant now = Instant.now();
        int position = positionBase;
        int admitted = 0;
        int prepared = 0;
        int superseded = 0;
        for (RoutedMessage routedMessage : routed) {
            int unitPosition = position++;
            if (routedMessage.decision() != InAppRoutingDecision.ADMIT || admitted >= TOP_N_PER_RECIPIENT) {
                continue;
            }
            admitted++;
            if (feedbackRepository.existsByAgentJobIdAndPosition(agentJobId, unitPosition)) {
                // A re-run reaching a unit it already wrote must not supersede a second time: the row it
                // would retire is the one this very unit replaced on the first pass.
                continue;
            }
            ComposedInAppMessage message = routedMessage.message();
            String threadKey =
                    FeedbackThreadKey.forPractice(message.practiceSlug(), recipientUserId, FeedbackChannel.IN_APP);
            // The claim and the write below are one swap, and this method's REQUIRES_NEW transaction is
            // what makes them one: a retired card with no replacement leaves the recipient with less than
            // they had before the run.
            UUID replaces = routedMessage.replaces();
            FeedbackSupersession.Outcome outcome = replaces == null
                    ? FeedbackSupersession.Outcome.standalone()
                    : supersession.replaceOpen(workspaceId, replaces);
            if (outcome.retiredSomething()) {
                superseded++;
            }
            Feedback unit = feedbackRepository.save(Feedback.builder()
                    .agentJobId(agentJobId)
                    .workspaceId(workspaceId)
                    // Unanchored on purpose: the message is about a habit across several pieces of work,
                    // so naming one of them as "the" artifact would misdescribe what it is evidenced by.
                    // The bound observations carry the artifacts, which is where the evidence belongs.
                    .recipientUserId(recipientUserId)
                    .aboutUserId(recipientUserId)
                    .channel(FeedbackChannel.IN_APP)
                    .position(unitPosition)
                    .deliveryState(FeedbackDeliveryState.PREPARED)
                    .source(FeedbackSource.AGENT)
                    .body(body(message))
                    // Cross-run identity for this habit, so a later message about the same practice
                    // supersedes this one rather than stacking beside it.
                    .threadKey(threadKey)
                    // What this card retired: the chain is the temporal record of one habit being raised
                    // over time.
                    .replacesId(outcome.replacesId())
                    .createdAt(now)
                    .build());
            int ordinal = 0;
            for (Observation observation : routedMessage.evidence()) {
                if (observation.getId() == null) {
                    continue;
                }
                feedbackObservationRepository.insertIfAbsent(
                        unit.getId(), observation.getId(), EvidenceRole.PRIMARY.name(), ordinal++);
            }
            prepared++;
        }
        if (prepared > 0) {
            log.info(
                    "In-app feedback prepared: jobId={}, recipientUserId={}, units={}, superseded={}",
                    agentJobId,
                    recipientUserId,
                    prepared,
                    superseded);
        }
        return prepared;
    }

    /** The stored body — layout owned by {@link InAppFeedbackBody}, which is also what reads it back. */
    static String body(ComposedInAppMessage message) {
        return InAppFeedbackBody.render(message.title(), message.body(), message.nextStep());
    }
}
