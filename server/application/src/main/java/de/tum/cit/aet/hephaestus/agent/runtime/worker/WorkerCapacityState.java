package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import de.tum.cit.aet.hephaestus.core.runtime.worker.protocol.CapacityReport;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-worker in-flight count of review jobs. Read by the capacity reporter, drain coordinator, and
 * {@code AgentJobExecutor}; the resolved max comes from {@link WorkerProperties}.
 */
public final class WorkerCapacityState {

    private final AtomicInteger inFlightReview = new AtomicInteger();
    private final int reviewMax;

    public WorkerCapacityState(WorkerProperties properties) {
        this.reviewMax = properties.capacity().resolveReviewMax();
    }

    public void claimReview() {
        inFlightReview.incrementAndGet();
    }

    /**
     * Idempotent: returns when the counter is already at 0 instead of throwing, because release
     * paths fire from defensive code (e.g. {@code AgentJobExecutor.releaseCapacity}) that can't
     * tolerate an exception, and the early-shutdown / double-release window is real but harmless.
     */
    public void releaseReview() {
        while (true) {
            int current = inFlightReview.get();
            if (current <= 0) {
                return;
            }
            if (inFlightReview.compareAndSet(current, current - 1)) {
                return;
            }
        }
    }

    public CapacityReport snapshot() {
        int review = inFlightReview.get();
        return new CapacityReport(reviewMax, review, Math.max(0, reviewMax - review));
    }

    public int reviewMax() {
        return reviewMax;
    }
}
