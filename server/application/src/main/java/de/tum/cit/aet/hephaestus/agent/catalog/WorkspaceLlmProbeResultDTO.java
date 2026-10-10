package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Workspace-framed "Test connection" result. Deliberately narrower than {@link LlmProbeResultDTO}: a
 * workspace admin sees the model count, never the provider's raw model id list.
 */
@Schema(description = "Result of testing your AI provider connection")
public record WorkspaceLlmProbeResultDTO(
        @NonNull @Schema(description = "Whether the provider answered")
        Boolean reachable,

        @NonNull @Schema(description = "How many models the provider listed (0 if unreachable)")
        Integer modelCount,

        @Nullable @Schema(description = "HTTP status returned by the provider, if any")
        Integer statusCode,

        @Nullable @Schema(description = "Human-readable diagnostic when not reachable")
        String message) {
    static WorkspaceLlmProbeResultDTO from(LlmProbeResultDTO raw) {
        if (!raw.reachable()) {
            return new WorkspaceLlmProbeResultDTO(false, 0, raw.statusCode(), raw.message());
        }
        return new WorkspaceLlmProbeResultDTO(true, raw.models().size(), raw.statusCode(), null);
    }
}
