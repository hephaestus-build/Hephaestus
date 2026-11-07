package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

public record ResultSourceCapture(
        UUID reviewId,
        long practiceId,
        long itemId,
        ReviewKind reviewKind,
        @Nullable JsonNode practiceDecision,
        @Nullable JsonNode itemDetail) {
    public @Nullable JsonNode decision() {
        var itemDecision = itemDetail == null ? null : itemDetail.get("captureDecision");
        return itemDecision != null && itemDecision.isObject() ? itemDecision : practiceDecision;
    }
}
