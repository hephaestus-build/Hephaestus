package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.function.Supplier;

/** Producers record the stable references on the exact rows they copy, before formatting their content. */
public interface PersonDataCopyRecorder {
    void recordUser(long userId);

    void recordIdentity(PersonCopyIdentity identity);

    void recordRepository(long repositoryId);

    <T> Captured<T> capture(Supplier<T> producer);

    Capture begin();

    interface Capture extends AutoCloseable {
        List<PersonCopyIdentity> identities();

        List<Long> repositoryIds();

        /** Installs a durable receipt writer; producers call it before copying each referenced row. */
        void onChange(Runnable writer);

        @Override
        void close();
    }

    record Captured<T>(T value, List<PersonCopyIdentity> identities) {
        public Captured {
            identities = List.copyOf(identities);
        }
    }
}
