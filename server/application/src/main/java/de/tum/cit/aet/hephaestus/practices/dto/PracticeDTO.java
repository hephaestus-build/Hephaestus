package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewValidation;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "Practice definition for evaluating developer contributions")
public record PracticeDTO(
        @NonNull @Schema(description = "Practice ID") Long id,

        @NonNull @Schema(description = "URL-safe identifier unique within workspace")
        String slug,

        @NonNull @Schema(description = "Human-readable name")
        String name,

        @NonNull
        @Schema(description = "The one occasion this practice is reviewed on, with the evidence that review reads")
        List<PracticeBinding> bindings,

        @NonNull @Schema(description = "Practice review criteria")
        String criteria,

        @Nullable @Schema(description = "TypeScript/Node precompute script for static analysis before AI review")
        String precomputeScript,

        @NonNull PracticeAutomatedReviewPolicy automatedReviewPolicy,
        @NonNull PracticeAutomatedReviewValidation automatedReviewValidation,

        @NonNull
        @Schema(description = "Kind of work this practice reviews, read off its bindings", example = "scm.pull_request")
        ArtifactKind artifactKind,

        @Nullable @Schema(description = "Slug of the practice group this practice is bound to, if any")
        String groupSlug,

        @NonNull @Schema(description = "Position within its group (lowest first); ties broken by name")
        Integer displayOrder,

        @Nullable @Schema(description = "Developer-facing rationale (developer layer)")
        String whyItMatters,

        @Nullable @Schema(description = "Developer-facing exemplar (developer layer)")
        String whatGoodLooksLike,

        @NonNull
        @Schema(
                description = "How much autonomy the system has over this practice, whether that was set here or "
                        + "inherited from its group or workspace, and which level decided it")
        AutonomyAssignmentDTO autonomy,

        @NonNull @Schema(description = "Timestamp when the practice was created")
        Instant createdAt,

        @NonNull @Schema(description = "Timestamp when the practice was last updated")
        Instant updatedAt,

        @Nullable CatalogOriginDTO catalogOrigin,
        @NonNull PracticeDeliveryBehavior deliveryBehavior) {
    /**
     * @param workspaceDefault the workspace's effective default autonomy, the bottom of the inheritance chain.
     *     Passed in rather than looked up here so one response resolves it once, and so this stays a pure
     *     mapping.
     */
    public static PracticeDTO from(
            Practice practice, @Nullable CatalogOriginDTO catalogOrigin, PracticeAutonomy workspaceDefault) {
        return new PracticeDTO(
                practice.getId(),
                practice.getSlug(),
                practice.getName(),
                practice.getBindings(),
                practice.getCriteria(),
                practice.getPrecomputeScript(),
                practice.getAutomatedReviewPolicy(),
                PracticeAutomatedReviewValidation.authorDeclared(practice.getSlug(), PracticeDefinition.from(practice)),
                practice.getArtifactKind(),
                practice.getGroup() != null ? practice.getGroup().getSlug() : null,
                practice.getDisplayOrder(),
                practice.getWhyItMatters(),
                practice.getWhatGoodLooksLike(),
                AutonomyAssignmentDTO.of(
                        AutonomyResolver.resolvePractice(practice, workspaceDefault), practice.getAutonomy()),
                practice.getCreatedAt(),
                practice.getUpdatedAt(),
                catalogOrigin,
                practice.getDeliveryBehavior());
    }
}
