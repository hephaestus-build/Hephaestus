package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Who reads a review. A team review names its team project; no other audience names one. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewAudienceDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReviewAudience kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        UUID teamProjectId) {
    public ReviewAudienceDTO {
        if ((kind == ReviewAudience.TEAM) != (teamProjectId != null)) {
            throw new IllegalArgumentException("Only a team audience names a team project");
        }
    }
}
