package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;

/** Producers record the stable references on the exact rows they copy, before formatting their content. */
public interface PersonDataCopyRecorder {
    void recordUser(long userId);

    void recordIdentity(PersonCopyIdentity identity);

    void recordRepository(long repositoryId);

    Capture begin();

    interface Capture extends AutoCloseable {
        List<PersonCopyIdentity> identities();

        List<Long> repositoryIds();

        /** Installs a durable receipt writer; producers call it before copying each referenced row. */
        void onChange(Runnable writer);

        @Override
        void close();
    }
}
