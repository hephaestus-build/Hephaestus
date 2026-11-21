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
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides which of the developer's threads on an item Hephaestus resolves or reopens, after the seal recorded problem
 * lineage or a person marked a result. A thread resolves only when every problem its note cites is No longer present,
 * and reopens when one of them is present again. A person who marks the proof incorrect takes back its claim, so a
 * resolved thread whose problems that result proved gone reopens too. A thread that a person took over is never
 * touched. The decision runs after the commit through the event publication registry: it never undoes the result it
 * follows, and a failed decision is delivered again.
 */
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

    record Payload(UUID resolutionId, int generation) {}

    @ApplicationModuleListener
    public void recorded(ProblemLineageRecorded event) {
        decide(event.workspaceId(), event.resultId(), null);
    }

    @ApplicationModuleListener
    public void marked(ResultMarkChanged mark) {
        if (mark.kind() == ResultMarkKind.INCORRECT)
            decide(mark.workspaceId(), mark.resultId(), mark.removed() ? null : mark.resultId());
    }

    /**
     * Decides for every thread of the developer on the result's item. {@code withdrawnProof} is a result that a person
     * just marked incorrect: a resolved thread whose problems that result proved gone, and that lost its proof with it,
     * reopens. A mark on a result that the note itself rests on reopens nothing: the note is retracted instead.
     */
    private void decide(long workspaceId, UUID resultId, @Nullable UUID withdrawnProof) {
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
            else if (verdict == FeedbackThreadEvidence.Verdict.PRESENT
                    || (withdrawnProof != null && evidence.proved(workspaceId, withdrawnProof, cited)))
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
        } else {
            int asked = row.getGeneration();
            if (!row.ask(resolve, thread.getCopyId(), now)) {
                // A withdrawn resolve that never started leaves nothing to undo: its waiting action goes too.
                if (row.getGeneration() != asked) actions.cancelPending(workspaceId, LANE + row.getId());
                return;
            }
        }
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
