package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.UUID;

/** Producers record the stable references on the exact rows they copy, before formatting their content. */
public interface PersonDataCopyRecorder {
    void recordUser(long userId);

    void recordIdentity(PersonCopyIdentity identity);

    void recordRepository(long repositoryId);

    /**
     * A copied row was written by another review job, whose own copy receipt indexes the people its words may quote.
     * The owner of the receipts resolves the dependency; the recorder only keeps the job key.
     */
    void recordCopiedJob(UUID jobId);

    Capture begin();

    interface Capture extends AutoCloseable {
        List<PersonCopyIdentity> identities();

        List<Long> repositoryIds();

        List<UUID> copiedJobIds();

        /** Installs a durable receipt writer; producers call it before copying each referenced row. */
        void onChange(Runnable writer);

        @Override
        void close();
    }
}
