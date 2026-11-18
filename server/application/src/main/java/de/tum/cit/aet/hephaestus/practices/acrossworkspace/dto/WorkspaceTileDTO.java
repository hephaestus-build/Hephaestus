package de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "One figure: the reader's own value, then the middle half of the developers counted")
public record WorkspaceTileDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The reader's own value")
        int yours,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                description = "The workspace's middle half; absent while fewer than 3 developers are counted")
        @Nullable
        WorkspaceRangeDTO middle) {}
