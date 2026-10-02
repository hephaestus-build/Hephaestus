package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Capture admission and the frozen erasure scope must be serialized across all runtime roles. */
public interface PersonDataCopyFence {
    Lease capture();

    Lease erase();

    void holdForErasure();

    interface Lease extends AutoCloseable {
        /** Uses the leased session outside a source reader's transaction; never closes the session. */
        org.springframework.jdbc.core.JdbcOperations jdbc();

        @Override
        void close();
    }
}
