package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Create an instance LLM provider connection")
public record CreateLlmConnectionRequestDTO(
        @Nullable
        @Size(max = 63)
        @Schema(description = "Optional internal slug; generated from displayName when omitted")
        String slug,

        @NonNull @NotBlank @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @NotBlank @Size(max = 2048) @Schema(description = "Provider base URL")
        String baseUrl,

        @NonNull
        @NotBlank
        @Pattern(
                regexp = "openai-completions|openai-responses",
                message = "apiProtocol must be one of openai-completions, openai-responses")
        @Schema(description = "Wire protocol", example = "openai-responses")
        String apiProtocol,

        @Nullable @Schema(description = "Credential shape (default BEARER)")
        LlmAuthMode authMode,

        @Nullable @Schema(description = "API key (write-only; never returned)")
        String apiKey,

        @Nullable @Schema(description = "Whether the connection is enabled (default false)")
        Boolean enabled) {}
