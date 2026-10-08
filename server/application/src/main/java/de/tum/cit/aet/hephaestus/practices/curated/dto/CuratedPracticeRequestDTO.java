package de.tum.cit.aet.hephaestus.practices.curated.dto;

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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(
        description = "A complete curated practice definition",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record CuratedPracticeRequestDTO(
        @NotBlank(message = "Name is required")
        @Size(min = 3, max = 128, message = "Name must be between 3 and 128 characters")
        @NonNull
        String name,

        @NotNull(message = "Choose at least one review moment")
        @Size(min = 1, message = "Choose at least one review moment")
        @NonNull
        List<SignalName> signals,

        @NotNull(message = "Evidence requirements are required") @NonNull
        List<@Valid PracticeEvidenceRequirement> evidenceRequirements,

        @Schema(description = ReviewWhen.DESCRIPTION) @Nullable
        Map<String, Set<String>> reviewWhen,

        @Nullable ActorRole subject,
        @Valid @Nullable PracticePrecondition precondition,

        @NotBlank(message = "Criteria is required")
        @Size(max = 50000, message = "Criteria must be at most 50000 characters")
        @NonNull
        String criteria,

        @Size(
                max = PracticeDefinition.MAX_PRECOMPUTE_SCRIPT_LENGTH,
                message = "Precompute script must be at most 100000 characters")
        @Nullable
        String precomputeScript,

        @Valid
        @Schema(
                description =
                        "Versioned review settings; omit to use the recommended settings for the selected work type")
        @Nullable
        PracticeAutomatedReviewPolicy automatedReviewPolicy,

        @Size(max = 2000, message = "Why it matters must be at most 2000 characters") @Nullable
        String whyItMatters,

        @Size(max = 2000, message = "What good looks like must be at most 2000 characters") @Nullable
        String whatGoodLooksLike,

        @Size(max = 64, message = "Group slug must be at most 64 characters") @Nullable
        String groupSlug,

        @Schema(description = "Explicit intent to change the gate or the person judged") @Nullable
        Set<DefinitionChange> definitionChanges,

        @Valid @Nullable PracticeDeliveryBehavior deliveryBehavior,

        @Schema(description = "Developer-facing picture of the practice; guidance, never review rules") @Nullable
        PracticeVisual visual,

        @Schema(description = "Read more text and its figures; guidance, never review rules") @Nullable
        PracticeGuide guide)
        implements ClosedPracticeInput {
    public CuratedPracticeRequestDTO(
            String name,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            @Nullable Map<String, Set<String>> reviewWhen,
            @Nullable ActorRole subject,
            @Nullable PracticePrecondition precondition,
            String criteria,
            @Nullable String precomputeScript,
            @Nullable PracticeAutomatedReviewPolicy automatedReviewPolicy,
            @Nullable String whyItMatters,
            @Nullable String whatGoodLooksLike,
            @Nullable String groupSlug,
            @Nullable Set<DefinitionChange> definitionChanges) {
        this(
                name,
                signals,
                evidenceRequirements,
                reviewWhen,
                subject == null ? ActorRole.AUTHOR : subject,
                precondition,
                criteria,
                precomputeScript,
                automatedReviewPolicy,
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                definitionChanges,
                null,
                null,
                null);
    }

    public PracticeDefinition definition(
            PracticeAutomatedReviewPolicy resolvedEvidence, Map<String, Set<String>> defaultReviewWhen) {
        return new PracticeDefinition(
                name,
                signals,
                evidenceRequirements,
                defaultReviewWhen,
                subject == null ? ActorRole.AUTHOR : subject,
                precondition,
                criteria,
                precomputeScript,
                resolvedEvidence,
                whyItMatters,
                whatGoodLooksLike,
                groupSlug,
                deliveryBehavior == null ? PracticeDeliveryBehavior.DEFAULT : deliveryBehavior,
                visual,
                guide);
    }
}
