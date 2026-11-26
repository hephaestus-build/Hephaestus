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

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true,
                description = "The identity of the current version. The upgrade and every write give each version one,"
                        + " so it is null only for a version written outside them")
        @Nullable
        PracticeIdentityDTO identity,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Whether the identity comes from the catalog, so it means the same in other workspaces")
        boolean portable,

        Instant createdAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        Instant updatedAt) {}
