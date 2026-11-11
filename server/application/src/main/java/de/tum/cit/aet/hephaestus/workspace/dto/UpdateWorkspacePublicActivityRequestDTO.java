package de.tum.cit.aet.hephaestus.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(description = "Only supplied non-null settings change. Turning the page off resets the other settings.")
public record UpdateWorkspacePublicActivityRequestDTO(
        @Schema(nullable = true) @Nullable Boolean publicActivityEnabled,
        @Schema(nullable = true) @Nullable Boolean allowSearchEngines,
        @Schema(nullable = true) @Nullable Boolean contributorsWithoutAccount) {}
