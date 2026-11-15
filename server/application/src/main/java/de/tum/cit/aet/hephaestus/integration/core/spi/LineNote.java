package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A note to post. {@code provenance} is the signed record of {@code body}; {@code null} for a note without one. */
public record LineNote(
        UUID copyId,
        FeedbackAnchor anchor,
        String body,
        String writeKey,
        String writeMarker,
        @Nullable String provenance) {
    public LineNote(UUID copyId, FeedbackAnchor anchor, String body, String writeKey, String writeMarker) {
        this(copyId, anchor, body, writeKey, writeMarker, null);
    }

    public LineNote {

        if (writeKey.isBlank()) throw new IllegalArgumentException("Note write key is blank");
        if (writeMarker.isBlank()) throw new IllegalArgumentException("Note write marker is blank");
    }
}
