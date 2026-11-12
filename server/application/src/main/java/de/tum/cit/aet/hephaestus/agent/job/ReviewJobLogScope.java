package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/** The review attempt of a claim or sandbox callback; restores its caller's complete log context. */
final class ReviewJobLogScope implements AutoCloseable {
    private final @Nullable Map<String, String> previous = MDC.getCopyOfContextMap();

    ReviewJobLogScope(ReviewJob job, AgentRunFence fence) {
        MDC.put(StructuredLogKeys.WORKSPACE_ID, Long.toString(job.getWorkspaceId()));
        MDC.put(StructuredLogKeys.JOB_ID, job.getId().toString());
        MDC.put(StructuredLogKeys.JOB_ATTEMPT, Integer.toString(ReviewRunKind.attempt(fence.epoch())));
        MDC.put(StructuredLogKeys.REVIEW_ID, job.getReviewId().toString());
        MDC.put(StructuredLogKeys.RUN_ID, fence.runId().toString());
        MDC.put(StructuredLogKeys.RUN_EPOCH, Integer.toString(fence.epoch()));
        MDC.put(StructuredLogKeys.SUBJECT_KEY, ReviewRunKind.subject(job.getReviewId()));
    }

    @Override
    public void close() {
        if (previous == null) MDC.clear();
        else MDC.setContextMap(previous);
    }
}
