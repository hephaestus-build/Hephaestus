package de.tum.cit.aet.hephaestus.mentor.spi;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * What a transport read from the provider for one leased attempt, outside any transaction. It proves the asker at one
 * time for that attempt and lease epoch only; it never selects a recipient. Unlike {@link ProviderObservation}, it
 * names no effect.
 */
public sealed interface AttemptObservation {
    record Observed(
            UUID attemptId,
            long leaseEpoch,
            String routeFingerprint,
            Set<String> provenReaders,
            long readerEpoch,
            Instant observedAt)
            implements AttemptObservation {
        public Observed {
            provenReaders = Set.copyOf(provenReaders);
        }
    }

    /** The provider evidence was missing, malformed, deleted or did not match the target. */
    record Refused(String reason) implements AttemptObservation {}
}
