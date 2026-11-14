package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** A provider's current source facts that differ from an ordinary record capture. */
public interface ItemFingerprintReader {
    boolean supports(ItemRef item);

    /**
     * Reads only what this server stores, so a caller may hold row locks.
     *
     * @param occasion the occasion that the capture read, as {@link ItemSource#recordFingerprints} reads it
     */
    Map<SourceKind, String> fingerprints(
            ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion, ObjectMapper mapper);

    /**
     * Brings this server's copy of what {@link #fingerprints} reads up to date with the provider, so that a locked read
     * after it answers from current data. It may call the provider, so it runs with no transaction. A reader whose
     * sources the server already keeps current does nothing.
     */
    default void sync(ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion) {}
}
