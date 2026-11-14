package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceFingerprint;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public interface ItemSource {
    boolean supports(ItemRef item);

    Map<Long, ItemLabel> labels(long workspaceId, Collection<ItemRef> items);

    Optional<ItemSnapshot> current(ItemRef item);

    /**
     * The version {@link #pin} would pin now, read from the stored work alone, without a snapshot, a capture or a
     * provider call. Empty where the source states no such version; unavailable work fails as {@link #pin} does.
     */
    default Optional<ItemVersion> currentVersion(ItemRef item) {
        return Optional.empty();
    }

    ItemSnapshot pin(ItemRef item, @Nullable ItemVersion requested);

    /**
     * Pins the version that the occasion {@code eventId} recorded on {@code item}. A source that retained the occasion
     * returns its state and roles as recorded, after it checks current access; any other source pins as
     * {@link #pin} does. Only an event review uses this; requested reviews and scans pin the current work.
     */
    default ItemSnapshot pinOccasion(ItemRef item, ItemVersion requested, UUID eventId) {
        return pin(item, requested);
    }

    /** Captures what {@code pinned} names; a request with an event ID captures what that occasion retained. */
    CaptureContribution capture(ItemSnapshot pinned, CaptureRequest request);

    JsonNode record(ItemSnapshot pinned, Map<String, byte[]> capturedFiles);

    /**
     * The fingerprints that a capture of {@code current} would record now for these record sources: each source read
     * alone, under the occasion that the capture read. A source that this server cannot read now has no fingerprint, so
     * the comparison is unverifiable, never current. A capture of an event review
     * reads its occasion's record, and the mirror's later status is not that record, so a read without the occasion
     * would find unchanged work changed.
     *
     * @param stored a capture that reads only what this server stores, with no provider call, so that a page load or a
     *     locked transaction never waits on a provider. It runs in its own transaction, or in the caller's.
     */
    static Map<SourceKind, String> recordFingerprints(
            BiFunction<ItemSnapshot, CaptureRequest, CaptureContribution> stored,
            ItemSnapshot current,
            Set<SourceKind> kinds,
            @Nullable UUID occasion,
            ObjectMapper mapper) {
        var fingerprints = new HashMap<SourceKind, String>();
        for (var kind : kinds) {
            var contribution = stored.apply(
                    current,
                    new CaptureRequest(
                            new UUID(0, 0),
                            new UUID(0, 0),
                            0,
                            Set.of(kind),
                            ClasspathSourceCatalogRegistry.CURRENT_VERSION_VALUE,
                            3,
                            null,
                            Set.of(kind),
                            occasion));
            var cleanup = contribution.cleanup();
            try (cleanup) {
                var fingerprint = SourceFingerprint.of(mapper, kind, contribution);
                if (fingerprint != null) fingerprints.put(kind, fingerprint);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not release the current source read", failure);
            }
        }
        return Map.copyOf(fingerprints);
    }
}
