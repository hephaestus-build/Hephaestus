package de.tum.cit.aet.hephaestus.practices.feedback.thread;

import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionRequest;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.result.ProblemLineageRecorded;
import de.tum.cit.aet.hephaestus.practices.result.ProblemLineageRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides, as the seal records problem lineage, which of the developer's threads on the item Hephaestus resolves or
 * reopens. A thread resolves only when every problem its note cites is No longer present, and it reopens when one of
 * them is present again. A thread that a person took over is never touched.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
class FeedbackThreadResolver {
    /** Each attempt reads the thread back first, so a repeated change is safe. */
    static final int MAX_ATTEMPTS = 8;

    private static final String LANE = "feedback-thread:";

    private final FeedbackThreadResolutionRepository resolutions;
    private final ResultRepository results;
    private final ReviewRepository reviews;
    private final ProblemLineageRepository lineage;
    private final AgentActions actions;
    private final ObjectMapper mapper;
    private final Clock clock;

    record Payload(UUID resolutionId, int generation) {}

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void recorded(ProblemLineageRecorded event) {
        long workspaceId = event.workspaceId();
        var result = results.findById(event.resultId())
                .filter(row -> row.getWorkspaceId() == workspaceId)
                .orElse(null);
        if (result == null || result.getDeveloperId() == null) return;
        long developerId = result.getDeveloperId();
        var threads = resolutions.findOwnedThreads(workspaceId, result.getItemId(), developerId);
        if (threads.isEmpty()) return;
        var cited = new HashMap<String, List<UUID>>();
        var traces = new HashMap<UUID, Optional<FeedbackCompositionTrace>>();
        for (var thread : threads) cited.put(thread.getThreadRef(), cited(workspaceId, thread, traces));
        var all = new HashSet<UUID>();
        cited.values().forEach(all::addAll);
        var gone = all.isEmpty() ? List.<UUID>of() : lineage.findGone(workspaceId, all);
        for (var thread : threads) {
            if (thread.getHumanControlled()) continue;
            var problems = Objects.requireNonNull(cited.get(thread.getThreadRef()));
            boolean resolve = !problems.isEmpty() && gone.containsAll(problems);
            decide(workspaceId, result.getItemId(), developerId, thread, resolve);
        }
    }

    private void decide(
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

    /**
     * The problems that the thread's note cites, from the composition support its revision was prepared with. Empty
     * when the support is missing or rests on a result without a problem: such a note never resolves.
     */
    private List<UUID> cited(
            long workspaceId,
            FeedbackThreadResolutionRepository.OwnedThread thread,
            Map<UUID, Optional<FeedbackCompositionTrace>> traces) {
        var trace = traces.computeIfAbsent(
                        thread.getFeedbackId(),
                        feedbackId -> reviews.findByIdAndWorkspaceId(thread.getReviewId(), workspaceId)
                                .map(review -> review.getChecks()
                                        .path("feedbackComposition")
                                        .path(feedbackId.toString()))
                                .filter(node -> node.isObject())
                                .map(node -> mapper.treeToValue(node, FeedbackCompositionTrace.class)))
                .orElse(null);
        if (trace == null) return List.of();
        var problems = new ArrayList<UUID>();
        for (var copy : trace.copies()) {
            if (!copy.kind().name().equals(thread.getKind()) || copy.position() != thread.getPosition()) continue;
            for (var support : copy.basedOn()) {
                if (support.problemId() == null) return List.of();
                problems.add(support.problemId());
            }
        }
        return List.copyOf(problems);
    }
}
