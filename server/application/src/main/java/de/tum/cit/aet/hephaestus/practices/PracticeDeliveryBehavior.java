package de.tum.cit.aet.hephaestus.practices;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** Delivery choices declared by the practice author, not inferred from its slug. */
public record PracticeDeliveryBehavior(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean summaryOnly,
        @Nullable String overlapGroup,
        @Nullable String redundantToSlug) {
    public static final PracticeDeliveryBehavior DEFAULT = new PracticeDeliveryBehavior(false, null, null);

    public PracticeDeliveryBehavior {
        if (overlapGroup != null && overlapGroup.isBlank()) {
            throw new IllegalArgumentException("overlapGroup must not be blank");
        }
        if (redundantToSlug != null && !redundantToSlug.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("redundantToSlug must be a practice slug");
        }
    }
}
