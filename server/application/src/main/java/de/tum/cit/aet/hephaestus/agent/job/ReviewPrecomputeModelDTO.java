package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One precompute model of a practice's script in one review, with the calls the proxy counted")
public record ReviewPrecomputeModelDTO(
        @NonNull @Schema(description = "The purpose whose binding serves this model")
        AgentPurpose purpose,

        @Schema(
                description = "Data handling tier of the slot that served the model in this review. Absent when the "
                        + "attempt froze no tier for the model of this purpose.")
        @Nullable
        DataHandlingTier tier,

        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Calls that the proxy forwarded to this model")
        long calls,

        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Input tokens of those calls")
        long inputTokens,

        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Output tokens of those calls")
        long outputTokens) {}
