package de.tum.cit.aet.hephaestus.integration.core.sync;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link SyncJobService} when a connection already has an active (PENDING/RUNNING)
 * {@link SyncJob} — either the service-level existence check or the partial-unique-index race
 * translated from a {@code DataIntegrityViolationException}.
 *
 * <p>Deliberately NOT {@code @ResponseStatus}-annotated: this is not itself an HTTP error. The manual
 * trigger endpoint's contract is idempotent-absorb (return the existing active job with 200, not an
 * error), so callers catch this and unwrap {@link #activeJob()} rather than letting it propagate to a
 * generic advice handler.
 */
public class SyncJobConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    // The caught conflict returns its managed entity to the same request; it never crosses a Java serialization
    // boundary.
    @SuppressWarnings("serial")
    private final SyncJob activeJob;

    public SyncJobConflictException(SyncJob activeJob) {
        this(activeJob, null);
    }

    public SyncJobConflictException(SyncJob activeJob, @Nullable Throwable cause) {
        super(
                "Connection " + activeJob.getConnection().getId() + " already has an active sync job: "
                        + activeJob.getId(),
                cause);
        this.activeJob = activeJob;
    }

    public SyncJob activeJob() {
        return activeJob;
    }
}
