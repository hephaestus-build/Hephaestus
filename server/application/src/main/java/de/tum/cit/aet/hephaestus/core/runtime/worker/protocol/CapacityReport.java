package de.tum.cit.aet.hephaestus.core.runtime.worker.protocol;

/**
 * Periodic worker → hub capacity announcement. {@code spareReview} is precomputed
 * ({@code = max(0, reviewMax - inFlightReview)}) so the dispatcher's selection path stays arithmetic-free.
 */
public record CapacityReport(int reviewMax, int inFlightReview, int spareReview) implements WorkerControlFrame {
    public CapacityReport {
        if (reviewMax < 0 || inFlightReview < 0 || spareReview < 0) {
            throw new IllegalArgumentException("CapacityReport fields must be non-negative");
        }
    }
}
