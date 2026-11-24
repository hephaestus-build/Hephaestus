package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReviewPersonDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$")
        String accountId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String displayName,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String avatarUrl,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable
        String providerLogin) {}
