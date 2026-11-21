package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResultStore {
    /**
     * Admits the candidates of an attempt. {@code captured} answers whether an earlier problem's quoted text is still in
     * the work that this attempt captured, so that problem lineage can record No longer present from complete proof.
     */
    AdmissionReceipt admit(
            long workspaceId,
            UUID reviewId,
            UUID jobId,
            int attempt,
            List<ResultCandidate> candidates,
            CapturedTextProbe captured);

    /** Admits candidates without a capture to compare with, so no earlier problem can be proven No longer present. */
    default AdmissionReceipt admit(
            long workspaceId, UUID reviewId, UUID jobId, int attempt, List<ResultCandidate> candidates) {
        return admit(workspaceId, reviewId, jobId, attempt, candidates, CapturedTextProbe.NONE);
    }

    int endCurrent(long workspaceId, long itemId, ResultEndReason reason, Instant at);

    UUID mark(long workspaceId, UUID resultId, ResultMarkKind kind, long actorId, String reason);

    boolean removeMark(long workspaceId, UUID markId, long actorId, String reason);
}
