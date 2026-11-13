package de.tum.cit.aet.hephaestus.practices.review.dto;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

public record ReviewContextDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$")
        String workspaceId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workspaceSlug,

        @Nullable ItemRefDTO item,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean requestAllowed,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canInspectReviewDetails,

        @Nullable String reasonDescription) {}
