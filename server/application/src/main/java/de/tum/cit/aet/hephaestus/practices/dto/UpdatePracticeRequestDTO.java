package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

@Schema(description = "Request to update a practice; omitted fields remain unchanged")
public record UpdatePracticeRequestDTO(
        @Size(min = 3, max = 128, message = "Name must be between 3 and 128 characters")
        @Pattern(regexp = ".*\\S.*", message = "Name must not be blank")
        @Schema(description = "Human-readable name", example = "PR Description Quality")
        @Nullable
        String name,

        @Size(
                min = 1,
                max = 1,
                message = "A practice is reviewed on one occasion. To read different evidence at a different moment, "
                        + "split this into two practices.")
        @Schema(description = "Replacement occasion and its evidence; omit to leave it unchanged")
        @Nullable
        List<@Valid PracticeBinding> bindings,

        @Size(max = 50000, message = "Criteria must be at most 50000 characters")
        @Pattern(regexp = ".*\\S.*", message = "Criteria must not be blank")
        @Schema(description = "Practice review criteria")
        @Nullable
        String criteria,

        @Size(
                max = PracticeDefinition.MAX_PRECOMPUTE_SCRIPT_LENGTH,
                message = "Precompute script must be at most 100000 characters")
        @Schema(description = "TypeScript/Node static analysis run before automated review")
        @Nullable
        String precomputeScript,

        @Valid
        @Schema(
                description = "Replacement review settings; omit to preserve them, or to take the recommended ones "
                        + "when the bindings move the practice to a different kind of work")
        @Nullable
        PracticeAutomatedReviewPolicy automatedReviewPolicy,

        @Size(max = 2000, message = "Why-it-matters must be at most 2000 characters")
        @Pattern(regexp = ".*\\S.*", message = "Why-it-matters must not be blank")
        @Schema(description = "Plain-language rationale shown to the developer")
        @Nullable
        String whyItMatters,

        @Size(max = 2000, message = "What-good-looks-like must be at most 2000 characters")
        @Pattern(regexp = ".*\\S.*", message = "What-good-looks-like must not be blank")
        @Schema(description = "Concrete example shown to the developer; not review criteria")
        @Nullable
        String whatGoodLooksLike,

        @Valid
        @Schema(description = "Catalog placement to apply with the definition update; omit to leave unchanged")
        @Nullable
        BindPracticeGroupRequestDTO group,

        @Schema(description = "Optional fields to clear before applying supplied values") @Nullable
        Set<ClearablePracticeField> clear) {}
