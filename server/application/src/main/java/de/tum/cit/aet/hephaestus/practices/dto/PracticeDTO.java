package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeDTO(
        @Schema(pattern = "^[1-9][0-9]*$", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,

        String slug,
        String kind,
        PracticeVersionDTO currentVersion,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        String groupSlug,

        int position,
        FeedbackModeAssignmentDTO feedbackMode,
        CatalogOriginDTO catalogOrigin,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Where the practice is edited. A practice edited in the practice repository is read-only"
                        + " here, except for its feedback mode and position")
        PracticeEditedIn editedIn,

        Instant createdAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        Instant updatedAt) {}
