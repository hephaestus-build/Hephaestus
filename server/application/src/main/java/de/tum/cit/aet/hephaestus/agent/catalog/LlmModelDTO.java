package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Instance catalog model projection. {@code grantedWorkspaceIds} is only populated when
 * {@code visibility} is {@code GRANTED}; it is empty for a model shared with all workspaces.
 */
@Schema(description = "Instance catalog model")
public record LlmModelDTO(
        @NonNull @Schema(description = "Model id") Long id,

        @NonNull @Schema(description = "Owning connection id")
        Long connectionId,

        @NonNull @Schema(description = "Owning connection's display name")
        String connectionDisplayName,

        @NonNull @Schema(description = "Unique slug within the connection")
        String slug,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Schema(description = "Model brand declared by an admin; display only")
        AiModelBrand brand,

        @NonNull @Schema(description = "Upstream provider model id")
        String upstreamModelId,

        @Nullable @Schema(description = "Probed forced_tool_choice capability; null until established")
        Boolean forcedToolChoice,

        @Nullable @Schema(description = "Context window an admin set; null leaves it to the provider's metadata")
        Integer contextWindow,

        @Nullable @Schema(description = "Maximum output tokens an admin set; null leaves it to the provider's metadata")
        Integer maxOutputTokens,

        @NonNull @Schema(description = "Token limits the model runs with, each with its source")
        LlmModelLimitsDTO limits,

        @Nullable
        @Schema(
                description =
                        "Reasoning effort requested of the model; null sends none, the provider's default applies")
        ReasoningEffort reasoningEffort,

        @Nullable @Schema(description = "Who operates the systems the work is sent to; null until declared")
        LlmDataOperator operatedBy,

        @Nullable @Schema(description = "Admin-only note: region, agreement, renewal date")
        String dataHandlingNote,

        @NonNull @Schema(description = "Data-handling tier derived from the operator; UNDECLARED until it is set")
        DataHandlingTier dataHandlingTier,

        @NonNull @Schema(description = "Share with all workspaces (PUBLIC) or only selected ones (GRANTED)")
        ModelVisibility visibility,

        @NonNull @Schema(description = "Workspace ids shared with; only meaningful when visibility is GRANTED")
        List<Long> grantedWorkspaceIds,

        @NonNull @Schema(description = "Active toggle") Boolean enabled,

        @NonNull @Schema(description = "Whether the model can run now, or the first reason it cannot")
        LlmModelReadiness readiness,

        @Nullable @Schema(description = "Current price; null if none has ever been set")
        LlmModelPriceDTO currentPrice,

        @NonNull @Schema(description = "Creation timestamp") Instant createdAt,

        @Nullable @Schema(description = "Last update timestamp")
        Instant updatedAt,

        @Schema(description = "The pool whose capacity the model's calls share; a model has its own by default")
        Long callPoolId) {
    public static LlmModelDTO from(
            LlmModel model, @Nullable LlmModelPrice currentPrice, List<Long> grantedWorkspaceIds) {
        return new LlmModelDTO(
                model.getId(),
                model.getConnection().getId(),
                model.getConnection().getDisplayName(),
                model.getSlug(),
                model.getDisplayName(),
                model.getBrand(),
                model.getUpstreamModelId(),
                model.getForcedToolChoice(),
                model.getContextWindow(),
                model.getMaxOutputTokens(),
                LlmModelLimitsDTO.from(model.limits()),
                model.getReasoningEffort(),
                model.getDataHandling().getOperatedBy(),
                model.getDataHandling().getNote(),
                model.getDataHandlingTier(),
                model.getVisibility(),
                grantedWorkspaceIds,
                model.isEnabled(),
                LlmModelReadiness.of(
                        model,
                        model.getConnection().isEnabled(),
                        PricingMode.of(currentPrice),
                        model.getVisibility() == ModelVisibility.PUBLIC || !grantedWorkspaceIds.isEmpty()),
                currentPrice != null ? LlmModelPriceDTO.from(currentPrice) : null,
                model.getCreatedAt(),
                model.getUpdatedAt(),
                model.getCallPoolId());
    }
}
