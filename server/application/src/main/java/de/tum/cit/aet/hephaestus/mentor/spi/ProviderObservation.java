package de.tum.cit.aet.hephaestus.mentor.spi;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** What a transport read from the provider for one effect, outside any database transaction. It proves the readers of
 * that effect's route at one time; it never selects a recipient. */
public record ProviderObservation(
        EffectRef ref,
        String routeFingerprint,
        String nativeActorId,
        Set<String> provenReaders,
        long readerEpoch,
        Instant observedAt) {
    /** How long an observation proves its readers. */
    public static final Duration FRESH_FOR = Duration.ofSeconds(60);

    public ProviderObservation {
        provenReaders = Set.copyOf(provenReaders);
    }
}
