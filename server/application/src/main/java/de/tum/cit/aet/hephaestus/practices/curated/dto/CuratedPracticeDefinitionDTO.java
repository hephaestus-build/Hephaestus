package de.tum.cit.aet.hephaestus.practices.curated.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewValidation;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.ReviewWhen;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = ReviewWhen.DESCRIPTION)
        Map<String, Set<String>> reviewWhen,

        @NonNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        ActorRole subject,

        @Nullable PracticePrecondition precondition,
        @NonNull String criteria,

        @Schema(
                description = "Questions the review answers and the rules that decide the outcome; absent when the"
                        + " practice is not reviewed automatically")
        @Nullable
        PracticeJudgment judgment,

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
                definition.reviewWhen(),
                definition.subject(),
                definition.precondition(),
                definition.criteria(),
                definition.judgment(),
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                PracticeAutomatedReviewValidation.authorDeclared(practiceSlug, definition),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug(),
                definition.deliveryBehavior());
    }
}
