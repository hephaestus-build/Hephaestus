package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record ResultSourceCapture(
        UUID reviewId,
        long practiceId,
        long itemId,
        ReviewKind reviewKind,
        ReviewOrigin reviewOrigin,
        @Nullable UUID eventId,
        @Nullable JsonNode practiceDecision,
        @Nullable JsonNode itemDetail) {
    /** The occasion that this capture read, which every comparison with the current work reads too. */
    public @Nullable UUID occasion() {
        return ReviewSnapshot.occasion(reviewKind, reviewOrigin, eventId);
    }

    public @Nullable JsonNode decision() {
        var itemDecision = itemDetail == null ? null : itemDetail.get("captureDecision");
        return itemDecision != null && itemDecision.isObject() ? itemDecision : practiceDecision;
    }
}
