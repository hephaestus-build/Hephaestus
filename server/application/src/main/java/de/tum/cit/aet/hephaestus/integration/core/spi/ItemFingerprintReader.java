package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/** A provider's current source facts that differ from an ordinary record capture. */
public interface ItemFingerprintReader {
    boolean supports(ItemRef item);

    Map<SourceKind, String> fingerprints(ItemRef item, Set<SourceKind> kinds, ObjectMapper mapper);
}
