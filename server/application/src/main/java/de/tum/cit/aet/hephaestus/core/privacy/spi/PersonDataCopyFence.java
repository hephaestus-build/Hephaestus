package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcOperations;

/** Capture admission and the frozen erasure scope must be serialized across all runtime roles. */
public interface PersonDataCopyFence {
    Lease capture();

    Lease erase();

    /** Short READ_COMMITTED writes retain capture admission until their transaction commits. */
    void holdForCapture();

    void holdForErasure();

    /**
     * Whether the erasure step of {@code store} ran at or after {@code since} for any person, by the database clock.
     * Asked after {@link #holdForCapture()} in the same transaction, the answer holds until that transaction commits: a
     * copy read before the step and stored after it would escape the step.
     */
    boolean erasedSince(String store, Instant since);

    interface Lease extends AutoCloseable {
        /** Uses the leased session outside a source reader's transaction; never closes the session. */
        JdbcOperations jdbc();

        @Override
        void close();
    }
}
