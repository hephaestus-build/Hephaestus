package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Draft probe request: test an as-yet-unsaved connection against its {@code /models} endpoint
 * using the supplied credential. The credential is used only for the probe and never persisted.
 */
@Schema(description = "Draft connection probe using a supplied (never-persisted) credential")
public record ProbeLlmConnectionRequestDTO(
        @NonNull @NotBlank @Schema(description = "Provider base URL")
        String baseUrl,

        @NonNull @NotNull @Schema(description = "Wire protocol")
        LlmApiProtocol apiProtocol,

        @Nullable @Schema(description = "Credential shape (default BEARER)")
        LlmAuthMode authMode,

        @Nullable @Schema(description = "API key used only for this probe")
        String apiKey) {}
