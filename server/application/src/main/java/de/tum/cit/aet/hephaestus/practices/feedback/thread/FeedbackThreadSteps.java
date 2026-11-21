package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadChange;
import de.tum.cit.aet.hephaestus.practices.adapter.FeedbackThreadStateRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The database steps of one thread change, each in a transaction of its own. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackThreadSteps {
    private static final Duration REREAD = Duration.ofSeconds(30);
    private static final Duration HELD = Duration.ofMinutes(5);

    private final FeedbackThreadResolutionRepository resolutions;
    private final FeedbackCopyRepository copies;
    private final FeedbackRepository feedback;
    private final ResultRepository results;
    private final FeedbackVisibilityService visibility;
    private final FeedbackBasisRepository basis;
    private final EvidenceAuthorization authorization;
    private final FeedbackThreadEvidence evidence;
    private final PersonDataWriteFence privacy;
    private final FeedbackThreadStateRepository threadStates;
    private final FeedbackThreadMeters meters;
    private final Clock clock;

    record Plan(Feedback feedback, CopyReceipt copy, boolean resolved) {}

    /** The feedback and copy that the current ask changes, or null when the ask stopped or another replaced it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable Plan plan(long workspaceId, FeedbackThreadResolver.Payload ask) {
        var row = resolutions.lockById(ask.resolutionId(), workspaceId).orElse(null);
        if (row == null || !row.current(ask.generation())) return null;
        var copy = copies.findById(row.getCopyId())
                .filter(found -> found.getWorkspaceId() == workspaceId)
                .orElse(null);
        if (copy == null
                || copy.getState() != FeedbackCopyState.POSTED
                || copy.getExternalRef() == null
                || !row.getThreadRef().equals(copy.getThreadRef()))
            return stopped(row, ask, FeedbackThreadStopReason.GONE);
        var refusal = refusal(row, copy);
        if (refusal != null) return stopped(row, ask, refusal);
        var owner = feedback.findByIdAndWorkspaceId(copy.getFeedbackId(), workspaceId)
                .orElse(null);
        if (owner == null) return stopped(row, ask, FeedbackThreadStopReason.GONE);
        if (!permitted(workspaceId, owner, copy)) return stopped(row, ask, FeedbackThreadStopReason.NOT_PERMITTED);
        return new Plan(
                owner,
                new CopyReceipt(
                        copy.getId(),
                        CopyDisposition.POSTED,
                        copy.getExternalRef(),
                        copy.getExternalUrl(),
                        true,
                        null,
                        copy.getThreadRef()),
                row.isResolved());
    }

    /**
     * Records the writing account and the write fence together, just before the provider call. It checks again what
     * can change while the provider is asked who the account is. Null lets the write go ahead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public @Nullable ActionResult begin(
            long workspaceId,
            FeedbackThreadResolver.Payload ask,
            IntegrationKind provider,
            String actor,
            ActionAttempt attempt,
            long developer) {
        var row = resolutions.lockById(ask.resolutionId(), workspaceId).orElse(null);
        if (row == null || !row.current(ask.generation()))
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        var copy = copies.findById(row.getCopyId()).orElse(null);
        var refusal = copy == null ? FeedbackThreadStopReason.GONE : refusal(row, copy);
        if (refusal == null && !privacy.holdForAccountWrite(developer))
            refusal = FeedbackThreadStopReason.PERSON_SUPPRESSED;
        if (refusal != null) {
            stopped(row, ask, refusal);
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
        row.begin(ask.generation(), provider, actor, clock.instant());
        return null;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActionResult settle(long workspaceId, FeedbackThreadResolver.Payload ask, ThreadChange change) {
        var row = resolutions.lockById(ask.resolutionId(), workspaceId).orElse(null);
        return switch (change.outcome()) {
            case DONE -> {
                if (row != null && row.current(ask.generation())) {
                    row.done(ask.generation(), clock.instant());
                    meters.record(row.isResolved(), null);
                }
                yield new ActionResult.Done(null);
            }
            case GONE -> {
                if (row != null) stopped(row, ask, FeedbackThreadStopReason.GONE);
                yield new ActionResult.Failed(ActionStopReason.ABSENT);
            }
            case NOT_OWNED -> {
                if (row != null) stopped(row, ask, FeedbackThreadStopReason.UNSUPPORTED);
                yield new ActionResult.Failed(ActionStopReason.REJECTED);
            }
            case REPLIED -> {
                if (row != null) stopped(row, ask, FeedbackThreadStopReason.REPLIED);
                yield new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
            }
            case TAKEN -> {
                // A person resolved the thread, and its event may never reach Hephaestus: hand it over here. That holds
                // whichever ask found it, so even a superseded ask hands the thread over.
                if (row != null) {
                    if (row.current(ask.generation()))
                        meters.record(row.isResolved(), FeedbackThreadStopReason.HUMAN_CONTROLLED);
                    row.handOver(clock.instant());
                    threadStates.protect(workspaceId, row.getItemId(), row.getThreadRef());
                }
                yield new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
            }
            case DEFERRED -> new ActionResult.Defer(HELD);
            case TRANSIENT -> new ActionResult.Reread(REREAD);
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void stop(long workspaceId, FeedbackThreadResolver.Payload ask, FeedbackThreadStopReason reason) {
        resolutions.lockById(ask.resolutionId(), workspaceId).ifPresent(row -> stopped(row, ask, reason));
    }

    /** Why the thread must not change now: a person took it over, or its feedback was withdrawn or marked incorrect. */
    private @Nullable FeedbackThreadStopReason refusal(FeedbackThreadResolution row, FeedbackCopy copy) {
        if (resolutions.humanControlled(row.getWorkspaceId(), row.getItemId(), row.getThreadRef()))
            return FeedbackThreadStopReason.HUMAN_CONTROLLED;
        // A note whose own evidence a person marked incorrect is being retracted: its thread is no longer the claim.
        if (visibility.withdrawn(row.getWorkspaceId(), copy.getFeedbackId())
                || basis.isIncorrect(row.getWorkspaceId(), copy.getFeedbackId()))
            return FeedbackThreadStopReason.WITHDRAWN;
        return null;
    }

    /**
     * Whether the evidence of the change still permits feedback delivery: the results the note rests on, and the
     * results whose lineage decided that its problems are gone or back.
     */
    private boolean permitted(long workspaceId, Feedback owner, FeedbackCopy copy) {
        var cited = evidence.cited(
                workspaceId, owner.getReviewId(), owner.getId(), copy.getKind().name(), copy.getPosition());
        var support = new HashMap<UUID, Result>();
        results.findBasisOfFeedback(workspaceId, owner.getId()).forEach(result -> support.put(result.getId(), result));
        results.findByWorkspaceIdAndIdIn(
                        workspaceId, evidence.standing(workspaceId, cited).deciding())
                .forEach(result -> support.put(result.getId(), result));
        return !support.isEmpty()
                && authorization
                                .permitsAll(
                                        workspaceId,
                                        support.values().stream()
                                                .map(Result::facts)
                                                .toList(),
                                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)
                                .size()
                        == support.size();
    }

    private @Nullable Plan stopped(
            FeedbackThreadResolution row, FeedbackThreadResolver.Payload ask, FeedbackThreadStopReason reason) {
        if (!row.current(ask.generation())) return null;
        row.stop(ask.generation(), reason, clock.instant());
        meters.record(row.isResolved(), reason);
        return null;
    }
}
