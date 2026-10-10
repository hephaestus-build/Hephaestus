package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.ReviewWhen;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "Request to create a new practice definition",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CreatePracticeRequestDTO(
        @NotBlank(message = "Slug is required")
        @Size(min = 3, max = 64, message = "Slug must be between 3 and 64 characters")
        @Pattern(
                regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$",
                message = "Slug must contain only lowercase alphanumeric characters and hyphens,"
                        + " must not start or end with a hyphen, and must not contain consecutive hyphens")
        @Schema(description = "URL-safe identifier unique within the workspace", example = "pr-description-quality")
        @Nullable
        String slug,

        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 128, message = "Name must be between 3 and 128 characters")
        @Schema(description = "Human-readable name", example = "PR Description Quality")
        @Nullable
        String name,

        @NotNull(message = "Choose at least one review moment")
        @Size(min = 1, message = "Choose at least one review moment")
        List<SignalName> signals,

        @NotNull(message = "Evidence requirements are required")
        List<@Valid PracticeEvidenceRequirement> evidenceRequirements,

        @Schema(description = ReviewWhen.DESCRIPTION) @Nullable
        Map<String, Set<String>> reviewWhen,

        @Nullable ActorRole subject,
        @Valid @Nullable PracticePrecondition precondition,

        @NotBlank(message = "Criteria is required")
        @Size(max = 50000, message = "Criteria must be at most 50000 characters")
        @Schema(description = "Practice review criteria")
        @Nullable
        String criteria,

        @Size(
                max = PracticeDefinition.MAX_PRECOMPUTE_SCRIPT_LENGTH,
                message = "Precompute script must be at most 100000 characters")
        @Schema(
                description =
                        "TypeScript precompute script that runs before the review and points it at places to check")
        @Nullable
        String precomputeScript,

        @Valid
        @Schema(
                description =
                        "Versioned review settings; omit to use the recommended ones for the work type the signals name")
        @Nullable
        PracticeAutomatedReviewPolicy automatedReviewPolicy,

        @Size(max = 2000, message = "Why-it-matters must be at most 2000 characters")
        @Schema(description = "Plain-language rationale shown to the developer")
        @Nullable
        String whyItMatters,

        @Size(max = 2000, message = "What-good-looks-like must be at most 2000 characters")
        @Schema(description = "Developer-facing exemplar; a concrete instance, not the review criteria")
        @Nullable
        String whatGoodLooksLike,

        @Schema(
                description = "Practice group to add the practice to. Omit or set to null for Unassigned.",
                nullable = true)
        @Nullable
        String groupSlug,

        @Valid @Nullable PracticeDeliveryBehavior deliveryBehavior)
        implements ClosedPracticeInput {
    public CreatePracticeRequestDTO(
            @Nullable String slug,
            @Nullable String name,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            @Nullable Map<String, Set<String>> reviewWhen,
            @Nullable ActorRole subject,
            @Nullable PracticePrecondition precondition,
            @Nullable String criteria,
            @Nullable String precomputeScript,
            @Nullable PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            @Nullable String groupSlug) {
        this(
                slug,
                name,
                signals,
                evidenceRequirements,
                reviewWhen,
                subject,
                precondition,
                criteria,
                precomputeScript,
                automatedReviewPolicy,
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                null);
    }
}
