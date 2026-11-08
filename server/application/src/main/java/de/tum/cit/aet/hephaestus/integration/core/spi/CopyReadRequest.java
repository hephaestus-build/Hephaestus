package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Provider references come from workspace-owned receipts, never from the composer. */
public record CopyReadRequest(
        UUID copyId,
        String externalRef,
        @Nullable String threadRef,
        String writeMarker,
        @Nullable FeedbackAnchor anchor) {
    public CopyReadRequest {
        if (externalRef.isBlank() || writeMarker.isBlank())
            throw new IllegalArgumentException("A copy read needs its recorded reference and marker");
    }
}
