package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyUpdateReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopyRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackVisibilityService;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carries out one ask to resolve or reopen a thread ({@link FeedbackThreadResolutionAdapter}). Each step that reads or
 * writes the database runs in its own transaction, and no transaction is open during a provider call. The writing
 * account is recorded with the write fence, before the write, so the thread event that the write causes is recognized
 * as Hephaestus's own whenever it arrives.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackThreadResolutionService {
    private static final Duration REREAD = Duration.ofSeconds(30);
    private static final Duration ACTOR_UNKNOWN = Duration.ofMinutes(1);
    private static final Duration SILENCED = Duration.ofMinutes(5);

    private final FeedbackThreadResolutionRepository resolutions;
    private final FeedbackCopyRepository copies;
    private final FeedbackRepository feedback;
    private final FeedbackBasisRepository basis;
    private final ResultRepository results;
    private final FeedbackVisibilityService visibility;
    private final EvidenceAuthorization evidence;
    private final PersonDataWriteFence privacy;
    private final FeedbackTargetResolver targets;
    private final List<LineNoteWriter> writers;
    private final FeedbackThreadMeters meters;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private record Plan(Feedback feedback, CopyReceipt copy, boolean resolved) {}

    ActionResult execute(ActionAttempt attempt, FeedbackThreadResolver.Payload ask) {
        long workspaceId = Objects.requireNonNull(attempt.workspaceId());
        var plan = transactions.execute(status -> plan(workspaceId, ask));
        if (plan == null) return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        FeedbackTarget target;
        try {
            target = targets.resolveExisting(plan.feedback());
        } catch (FeedbackTargetResolver.TargetUnavailable unavailable) {
            stop(workspaceId, ask, FeedbackThreadStopReason.GONE);
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        var matching = writers.stream()
                .filter(writer -> writer.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            stop(workspaceId, ask, FeedbackThreadStopReason.UNSUPPORTED);
            return new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED);
        }
        var writer = matching.getFirst();
        String actor = writer.actor(target);
        if (actor == null) return new ActionResult.Retry(ACTOR_UNKNOWN);
        var refused = transactions.execute(status -> begin(workspaceId, ask, actor, attempt, plan.feedback()));
        if (refused != null) return refused;
        CopyUpdateReceipt receipt;
        try {
            receipt = writer.resolveThread(target, plan.copy(), plan.resolved());
        } catch (OutboundEgressSuppressedException silenced) {
            return new ActionResult.Defer(SILENCED);
        }
        return Objects.requireNonNull(transactions.execute(status -> settle(workspaceId, ask, receipt)));
    }

    /** Ends the ask of an action that ended without confirming it, whoever ended it. */
    void ended(long workspaceId, FeedbackThreadResolver.Payload ask, ActionState state) {
        if (state == ActionState.DONE) return;
        stop(workspaceId, ask, FeedbackThreadStopReason.FAILED);
    }

    private @Nullable Plan plan(long workspaceId, FeedbackThreadResolver.Payload ask) {
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
        if (resolutions.humanControlled(workspaceId, row.getItemId(), row.getThreadRef()))
            return stopped(row, ask, FeedbackThreadStopReason.HUMAN_CONTROLLED);
        if (visibility.withdrawn(workspaceId, copy.getFeedbackId()))
            return stopped(row, ask, FeedbackThreadStopReason.WITHDRAWN);
        var owner = feedback.findByIdAndWorkspaceId(copy.getFeedbackId(), workspaceId)
                .orElse(null);
        if (owner == null) return stopped(row, ask, FeedbackThreadStopReason.GONE);
        // The thread speaks for the evidence the note rests on, so that evidence must still permit delivery.
        var support = results.findByWorkspaceIdAndIdIn(
                workspaceId,
                basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspaceId, owner.getId()).stream()
                        .map(FeedbackBasis::getResultId)
                        .distinct()
                        .toList());
        if (support.isEmpty()
                || evidence.permitsAll(
                                        workspaceId,
                                        support.stream().map(Result::facts).toList(),
                                        SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)
                                .size()
                        != support.size()) return stopped(row, ask, FeedbackThreadStopReason.NOT_PERMITTED);
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

    private @Nullable ActionResult begin(
            long workspaceId, FeedbackThreadResolver.Payload ask, String actor, ActionAttempt attempt, Feedback owner) {
        var row = resolutions.lockById(ask.resolutionId(), workspaceId).orElse(null);
        if (row == null || !row.current(ask.generation()))
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        if (!privacy.holdForAccountWrite(owner.getDeveloperId())) {
            stopped(row, ask, FeedbackThreadStopReason.PERSON_SUPPRESSED);
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        if (!attempt.fence().beginWrite()) return new ActionResult.Abandoned();
        row.begin(ask.generation(), actor, clock.instant());
        return null;
    }

    private ActionResult settle(long workspaceId, FeedbackThreadResolver.Payload ask, CopyUpdateReceipt receipt) {
        var row = resolutions.lockById(ask.resolutionId(), workspaceId).orElse(null);
        return switch (receipt.kind()) {
            case EDITED -> {
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
            case UNSUPPORTED -> {
                if (row != null) stopped(row, ask, FeedbackThreadStopReason.UNSUPPORTED);
                yield new ActionResult.Failed(ActionStopReason.REJECTED);
            }
            case TRANSIENT -> new ActionResult.Reread(REREAD);
        };
    }

    private void stop(long workspaceId, FeedbackThreadResolver.Payload ask, FeedbackThreadStopReason reason) {
        transactions.executeWithoutResult(status ->
                resolutions.lockById(ask.resolutionId(), workspaceId).ifPresent(row -> stopped(row, ask, reason)));
    }

    private @Nullable Plan stopped(
            FeedbackThreadResolution row, FeedbackThreadResolver.Payload ask, FeedbackThreadStopReason reason) {
        if (!row.current(ask.generation())) return null;
        row.stop(ask.generation(), reason, clock.instant());
        meters.record(row.isResolved(), reason);
        return null;
    }
}
