package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

/**
 * Hub → worker frame requesting prompt cancellation of a specific running job.
 *
 * <p>Job-scoped (carries a {@code jobId}) so the owning worker — and only the owning worker — stops
 * its container. The authoritative {@code agent_job} status transition is performed by the hub
 * before dispatching this frame; the frame exists purely to stop the container promptly rather than
 * waiting for it to finish naturally. A worker that does not own the job ignores it.
 *
 * @param jobId the job UUID (string form)
 * @param reason short human-readable reason, for worker-side logging
 */
public record CancelJob(String jobId, String reason) implements WorkerControlFrame {
    public CancelJob {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
    }
}
