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

    /** @param occasion the occasion that the capture read, as {@link ItemSource#recordFingerprints} reads it */
    Map<SourceKind, String> fingerprints(
            ItemRef item, Set<SourceKind> kinds, @Nullable UUID occasion, ObjectMapper mapper);
}
