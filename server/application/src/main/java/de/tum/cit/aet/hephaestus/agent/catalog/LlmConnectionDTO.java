package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Instance LLM connection projection. The stored API key is never serialized: {@code hasApiKey}
 * reports whether one is set and {@code apiKeyLast4} exposes only its final four characters for
 * recognition.
 */
@Schema(description = "Instance LLM provider connection (API key redacted)")
public record LlmConnectionDTO(
        @NonNull @Schema(description = "Connection ID") Long id,

        @NonNull @Schema(description = "Unique slug", example = "openai-prod")
        String slug,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @Schema(description = "Provider base URL") String baseUrl,

        @NonNull @Schema(description = "Wire protocol", example = "openai-responses")
        String apiProtocol,

        @NonNull @Schema(description = "Credential shape") LlmAuthMode authMode,

        @NonNull @Schema(description = "Whether an API key is stored")
        Boolean hasApiKey,

        @Nullable @Schema(description = "Last four characters of the stored API key, if any")
        String apiKeyLast4,

        @NonNull @Schema(description = "Whether the connection is enabled")
        Boolean enabled,

        @NonNull @Schema(description = "Creation timestamp") Instant createdAt,

        @Nullable @Schema(description = "Last update timestamp")
        Instant updatedAt) {
    public static LlmConnectionDTO from(LlmConnection connection) {
        String apiKey = connection.getApiKey();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        String last4 = apiKey != null && hasKey && apiKey.length() >= 4 ? apiKey.substring(apiKey.length() - 4) : null;
        return new LlmConnectionDTO(
                connection.getId(),
                connection.getSlug(),
                connection.getDisplayName(),
                connection.getBaseUrl(),
                connection.getApiProtocol(),
                connection.getAuthMode(),
                hasKey,
                last4,
                connection.isEnabled(),
                connection.getCreatedAt(),
                connection.getUpdatedAt());
    }
}
