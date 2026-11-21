package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.ProblemLineageRecorded;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkChanged;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides which of the developer's threads on an item Hephaestus resolves or reopens, after the seal recorded problem
 * lineage or a person marked a result. A thread resolves only when every problem its note cites is No longer present,
 * and reopens when one of them is present again. A person who marks the proof incorrect takes back its claim, so a
 * resolved thread whose problems no longer have proof reopens too. A thread that a person took over is never touched.
 * The decision runs after the commit, in its own transaction: it can never undo the result it follows.
 */
@Slf4j
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackThreadResolver {
    /** Each attempt reads the thread back first, so a repeated change is safe. */
    static final int MAX_ATTEMPTS = 8;

    static final String LANE = "feedback-thread:";

    private final FeedbackThreadResolutionRepository resolutions;
    private final ResultRepository results;
    private final FeedbackThreadEvidence evidence;
    private final AgentActions actions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    record Payload(UUID resolutionId, int generation) {}

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void recorded(ProblemLineageRecorded event) {
        decide(event.workspaceId(), event.resultId(), false);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void marked(ResultMarkChanged mark) {
        if (mark.kind() == ResultMarkKind.INCORRECT) decide(mark.workspaceId(), mark.resultId(), !mark.removed());
    }

    /**
     * Decides in a transaction of its own. A failure is logged and swallowed: the result already committed, and the
     * next review or mark of the item decides again, so a thread only keeps its state until then.
     */
    private void decide(long workspaceId, UUID resultId, boolean reopenWithoutProof) {
        try {
            transactions.executeWithoutResult(status -> decideNow(workspaceId, resultId, reopenWithoutProof));
        } catch (RuntimeException failed) {
            log.warn(
                    "Thread resolution decision failed: workspaceId={}, resultId={}, cause={}",
                    workspaceId,
                    resultId,
                    failed.getClass().getSimpleName());
        }
    }

    private void decideNow(long workspaceId, UUID resultId, boolean reopenWithoutProof) {
        var result = results.findById(resultId)
                .filter(row -> row.getWorkspaceId() == workspaceId)
                .orElse(null);
        if (result == null || result.getDeveloperId() == null) return;
        long developerId = result.getDeveloperId();
        long itemId = result.getItemId();
        for (var thread : resolutions.findOwnedThreads(workspaceId, itemId, developerId)) {
            if (resolutions.humanControlled(workspaceId, itemId, thread.getThreadRef())) continue;
            var cited = evidence.cited(
                    workspaceId, thread.getReviewId(), thread.getFeedbackId(), thread.getKind(), thread.getPosition());
            var verdict = evidence.standing(workspaceId, cited).verdict();
            if (verdict == FeedbackThreadEvidence.Verdict.GONE) ask(workspaceId, itemId, developerId, thread, true);
            else if (verdict == FeedbackThreadEvidence.Verdict.PRESENT || reopenWithoutProof)
                ask(workspaceId, itemId, developerId, thread, false);
        }
    }

    private void ask(
            long workspaceId,
            long itemId,
            long developerId,
            FeedbackThreadResolutionRepository.OwnedThread thread,
            boolean resolve) {
        var now = clock.instant();
        var row = resolutions
                .lockThread(workspaceId, itemId, thread.getThreadRef())
                .orElse(null);
        if (row == null) {
            if (!resolve) return;
            row = resolutions.save(FeedbackThreadResolution.resolve(
                    workspaceId, itemId, thread.getThreadRef(), thread.getCopyId(), now));
        } else if (!row.ask(resolve, thread.getCopyId(), now)) return;
        String lane = LANE + row.getId();
        actions.cancelPending(workspaceId, lane);
        actions.enqueue(new ActionRequest(
                ActionKind.RESOLVE_FEEDBACK_THREAD,
                workspaceId,
                lane,
                lane + ":" + row.getGeneration() + ":" + (row.isResolved() ? "RESOLVE" : "REOPEN"),
                mapper.valueToTree(new Payload(row.getId(), row.getGeneration())),
                developerId,
                MAX_ATTEMPTS,
                null,
                null));
    }
}
