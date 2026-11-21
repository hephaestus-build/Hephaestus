package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNoteWriter;
import de.tum.cit.aet.hephaestus.practices.feedback.delivery.FeedbackTargetResolver;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Carries out one ask to resolve or reopen a thread ({@link FeedbackThreadResolutionAdapter}). Each database step runs
 * in a transaction of its own ({@link FeedbackThreadSteps}), and no transaction is open during a provider call. The
 * writing account is recorded with the write fence, before the write, so the thread event that the write causes is
 * recognized as Hephaestus's own whenever it arrives.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class FeedbackThreadResolutionService {
    private static final Duration ACTOR_UNKNOWN = Duration.ofMinutes(1);

    private final FeedbackThreadSteps steps;
    private final FeedbackTargetResolver targets;
    private final List<LineNoteWriter> writers;

    ActionResult execute(ActionAttempt attempt, FeedbackThreadResolver.Payload ask) {
        long workspaceId = Objects.requireNonNull(attempt.workspaceId());
        var plan = steps.plan(workspaceId, ask);
        if (plan == null) return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        FeedbackTarget target;
        try {
            target = targets.resolveExisting(plan.feedback());
        } catch (FeedbackTargetResolver.TargetUnavailable unavailable) {
            steps.stop(workspaceId, ask, FeedbackThreadStopReason.GONE);
            return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        }
        var matching = writers.stream()
                .filter(writer -> writer.kind() == target.integration().kind())
                .toList();
        if (matching.size() != 1) {
            steps.stop(workspaceId, ask, FeedbackThreadStopReason.UNSUPPORTED);
            return new ActionResult.Cancelled(ActionStopReason.NOT_CONFIGURED);
        }
        var writer = matching.getFirst();
        String actor = writer.actor(target);
        if (actor == null) return new ActionResult.Retry(ACTOR_UNKNOWN);
        var refused =
                steps.begin(workspaceId, ask, actor, attempt, plan.feedback().getDeveloperId());
        if (refused != null) return refused;
        return steps.settle(workspaceId, ask, writer.resolveThread(target, plan.copy(), plan.resolved(), actor));
    }

    /** Ends the ask of an action that ended without confirming it, whoever ended it. */
    void ended(long workspaceId, FeedbackThreadResolver.Payload ask, ActionState state) {
        if (state != ActionState.DONE) steps.stop(workspaceId, ask, FeedbackThreadStopReason.FAILED);
    }
}
