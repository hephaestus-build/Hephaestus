package de.tum.cit.aet.hephaestus.core.runtime.hub;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CancelJob;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes a job cancellation to the worker that owns the running job, over the WSS control channel.
 * The caller (e.g. {@code AgentJobService.cancel}) performs the authoritative
 * {@code agent_job} status transition first; this just asks the owning worker to stop its container
 * promptly. If the owning worker is not connected to this hub, the cancel still takes effect via the
 * DB transition + reconciler/zombie-sweeper backstops — the container is simply not stopped early.
 */
public class WorkerJobCancelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WorkerJobCancelDispatcher.class);

    private final WorkerSessionRegistry registry;

    public WorkerJobCancelDispatcher(WorkerSessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * Send a {@link CancelJob} to {@code workerId} if it has a live session on this hub.
     *
     * @return {@code true} if the frame was dispatched to a connected worker
     */
    public boolean dispatch(@Nullable String workerId, UUID jobId, String reason) {
        if (workerId == null || workerId.isBlank()) {
            return false;
        }
        return registry.findByWorkerId(workerId)
                .map(session -> {
                    session.send(new CancelJob(jobId.toString(), reason));
                    log.info("Dispatched CancelJob for {} to worker {}", jobId, workerId);
                    return true;
                })
                .orElseGet(() -> {
                    log.debug(
                            "No live session for worker {} to cancel job {}; relying on DB + backstops",
                            workerId,
                            jobId);
                    return false;
                });
    }
}
