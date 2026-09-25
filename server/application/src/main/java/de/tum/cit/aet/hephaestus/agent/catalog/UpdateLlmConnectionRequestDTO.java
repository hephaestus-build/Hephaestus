package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Partial update of an instance LLM connection. Every field is optional; an absent (null)
 * field keeps its current value. The API key is write-only: an absent {@code apiKey} keeps the stored
 * key, and {@code clearApiKey=true} removes it (clearing wins over a supplied value).
 */
@Schema(description = "Update an instance LLM provider connection (all fields optional)")
public record UpdateLlmConnectionRequestDTO(
        @Nullable @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Schema(description = "New API key (write-only; never returned)")
        String apiKey,

        @Nullable @Schema(description = "Set true to clear the stored API key")
        Boolean clearApiKey,

        @Nullable @Schema(description = "Whether the connection is enabled")
        Boolean enabled,

        @Nullable @Schema(description = "Admin-declared connection platform")
        LlmConnectionPlatform connectionPlatform,

        @Nullable @Schema(description = "Set true to clear the declared connection platform")
        Boolean clearConnectionPlatform) {}
