package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import de.tum.cit.aet.hephaestus.practices.DefinitionChange;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeGuide;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.PracticeVisual;
import de.tum.cit.aet.hephaestus.practices.ReviewWhen;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "Request to update a practice; omitted fields remain unchanged",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record UpdatePracticeRequestDTO(
        @Size(min = 3, max = 128, message = "Name must be between 3 and 128 characters")
        @Pattern(regexp = ".*\\S.*", message = "Name must not be blank")
        @Schema(description = "Human-readable name", example = "PR Description Quality")
        @Nullable
        String name,

        @Size(min = 1, message = "Choose at least one review moment") @Nullable
        List<SignalName> signals,

        @Nullable List<@Valid PracticeEvidenceRequirement> evidenceRequirements,

        @Schema(description = ReviewWhen.DESCRIPTION) @Nullable
        Map<String, Set<String>> reviewWhen,

        @Nullable ActorRole subject,
        @Valid @Nullable PracticePrecondition precondition,

        @Size(max = 50000, message = "Criteria must be at most 50000 characters")
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "Criteria must not be blank")
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
                        + "when the signals move the practice to a different kind of work")
        @Nullable
        PracticeAutomatedReviewPolicy automatedReviewPolicy,

        @Size(max = 2000, message = "Why-it-matters must be at most 2000 characters")
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "Why-it-matters must not be blank")
        @Schema(description = "Plain-language rationale shown to the developer")
        @Nullable
        String whyItMatters,

        @Size(max = 2000, message = "What-good-looks-like must be at most 2000 characters")
        @Pattern(regexp = "[\\s\\S]*\\S[\\s\\S]*", message = "What-good-looks-like must not be blank")
        @Schema(description = "Concrete example shown to the developer; not review criteria")
        @Nullable
        String whatGoodLooksLike,

        @Valid
        @Schema(description = "Catalog placement to apply with the definition update; omit to leave unchanged")
        @Nullable
        BindPracticeGroupRequestDTO group,

        @Schema(description = "Optional fields to clear before applying supplied values") @Nullable
        Set<ClearablePracticeField> clear,

        @Schema(description = "Explicit intent to change the gate or the person judged") @Nullable
        Set<DefinitionChange> definitionChanges,

        @Valid @Nullable PracticeDeliveryBehavior deliveryBehavior,

        @Schema(description = "Developer-facing picture of the practice; guidance, never review rules") @Nullable
        PracticeVisual visual,

        @Schema(description = "Read more text and its figures; guidance, never review rules") @Nullable
        PracticeGuide guide)
        implements ClosedPracticeInput {
    public UpdatePracticeRequestDTO(
            @Nullable String name,
            @Nullable List<SignalName> signals,
            @Nullable List<PracticeEvidenceRequirement> evidenceRequirements,
            @Nullable Map<String, Set<String>> reviewWhen,
            @Nullable ActorRole subject,
            @Nullable PracticePrecondition precondition,
            @Nullable String criteria,
            @Nullable String precomputeScript,
            @Nullable PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            @Nullable BindPracticeGroupRequestDTO group,
            @Nullable Set<ClearablePracticeField> clear,
            @Nullable Set<DefinitionChange> definitionChanges) {
        this(
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
                group,
                clear,
                definitionChanges,
                null,
                null,
                null);
    }
}
