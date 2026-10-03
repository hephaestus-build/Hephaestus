package de.tum.cit.aet.hephaestus.core.privacy.spi;

import org.springframework.jdbc.core.JdbcOperations;

/** Capture admission and the frozen erasure scope must be serialized across all runtime roles. */
public interface PersonDataCopyFence {
    Lease capture();

    Lease erase();

    /** Short READ_COMMITTED writes retain capture admission until their transaction commits. */
    void holdForCapture();

    void holdForErasure();

    interface Lease extends AutoCloseable {
        /** Uses the leased session outside a source reader's transaction; never closes the session. */
        JdbcOperations jdbc();

        @Override
        void close();
    }
}
