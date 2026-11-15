package de.tum.cit.aet.hephaestus.integration.outline.connect;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

/** A new API key for the workspace's existing Outline connection. */
@Schema(description = "A new API key of the connected Outline team")
public record ReplaceOutlineTokenRequestDTO(
        @NonNull
        @NotBlank
        @Size(max = 512)
        @Schema(description = "Outline API key", requiredMode = Schema.RequiredMode.REQUIRED)
        String token) {}
