package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Partial update of a model's metadata. Every field is optional; an absent (null) field keeps
 * its current value. Pricing and sharing are updated through their own endpoints, not here.
 *
 * <p>Data handling is the exception: the operator and the note are replaced wholesale on every
 * update, so omitting the operator declares the model undeclared again.
 */
@Schema(description = "Update a model's metadata (all fields optional; pricing and sharing are separate)")
public record UpdateLlmModelRequestDTO(
        @Nullable @Size(max = 128) @Schema(description = "Human-readable name")
        String displayName,

        @Nullable @Min(1) @Schema(description = "Context window in tokens")
        Integer contextWindow,

        @Nullable @Min(1) @Schema(description = "Maximum output tokens")
        Integer maxOutputTokens,

        @Nullable
        @Schema(description = "Reasoning effort to request; null keeps the current one (see clearReasoningEffort)")
        ReasoningEffort reasoningEffort,

        @Nullable @Schema(description = "True clears the reasoning effort, so the provider's own default applies")
        Boolean clearReasoningEffort,

        @Nullable
        @Schema(description = "Who operates the systems the work is sent to; omit to leave the model undeclared")
        LlmDataOperator operatedBy,

        @Nullable @Size(max = 200) @Schema(description = "Admin-only note: region, agreement, renewal date")
        String dataHandlingNote,

        @Nullable @Schema(description = "Active toggle (off = existing settings stop working)")
        Boolean enabled,

        @Nullable @Schema(description = "Model brand declared by an admin; null keeps current")
        AiModelBrand brand,

        @Nullable @Schema(description = "Clear the declared model brand")
        Boolean clearBrand) {}
