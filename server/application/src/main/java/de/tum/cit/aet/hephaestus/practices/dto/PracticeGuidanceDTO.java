package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.CanonicalDigest;
import de.tum.cit.aet.hephaestus.practices.PracticeGuide;
import de.tum.cit.aet.hephaestus.practices.PracticeVisual;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Served on its own, so the developer's practice pages load the markup only for the practice that a developer opens.
 */
@Schema(description = "The visual and the guide of one practice, as the practice panel shows them")
public record PracticeGuidanceDTO(
        @NonNull @Schema(description = "URL-safe identifier unique within workspace")
        String practiceSlug,

        @Nullable PracticeVisual visual,
        @Nullable PracticeGuide guide) {
    public static PracticeGuidanceDTO from(Practice practice) {
        return new PracticeGuidanceDTO(practice.getSlug(), practice.getVisual(), practice.getGuide());
    }

    public String entityTag() {
        return new CanonicalDigest()
                .add(practiceSlug)
                .addNullable(visual == null ? null : visual.digest())
                .addNullable(guide == null ? null : guide.digest())
                .hex();
    }
}
