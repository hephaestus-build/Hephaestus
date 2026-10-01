package de.tum.cit.aet.hephaestus.practices.curated.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewValidation;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "A resolved curated practice definition")
public record CuratedPracticeDefinitionDTO(
        @NonNull String name,
        @NonNull ArtifactKind artifactKind,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<SignalName> signals,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<PracticeEvidenceRequirement> evidenceRequirements,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean onDrafts,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ActorRole subject,

        @Nullable PracticePrecondition precondition,
        @NonNull String criteria,
        @Nullable String precomputeScript,
        @NonNull PracticeAutomatedReviewPolicy automatedReviewPolicy,
        @NonNull PracticeAutomatedReviewValidation automatedReviewValidation,
        @Nullable String whyItMatters,
        @Nullable String whatGoodLooksLike,
        @Nullable String groupSlug,
        @NonNull PracticeDeliveryBehavior deliveryBehavior) {
    public static CuratedPracticeDefinitionDTO from(String practiceSlug, PracticeDefinition definition) {
        return new CuratedPracticeDefinitionDTO(
                definition.name(),
                definition.artifactKind(),
                definition.signals(),
                definition.evidenceRequirements(),
                definition.onDrafts(),
                definition.subject(),
                definition.precondition(),
                definition.criteria(),
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                PracticeAutomatedReviewValidation.authorDeclared(practiceSlug, definition),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug(),
                definition.deliveryBehavior());
    }
}
