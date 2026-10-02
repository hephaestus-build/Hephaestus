package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "Author-declared automated review and evidence sufficiency for one practice",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeAutomatedReview(
        @NonNull @NotNull @Schema(description = "Implementation Hephaestus uses for automated review")
        PracticeAutomatedReviewMode mode,

        @NonNull
        @NotNull
        @Schema(description = "Whether meeting every evidence requirement provides enough context to review the work")
        PracticeEvidenceSufficiency evidenceSufficiency)
        implements ClosedPracticeInput {
    public PracticeAutomatedReview {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(evidenceSufficiency, "evidenceSufficiency");
        boolean noAutomatedReview = mode == PracticeAutomatedReviewMode.NONE;
        boolean noEvidence = evidenceSufficiency == PracticeEvidenceSufficiency.NONE;
        if (noAutomatedReview != noEvidence) {
            throw new IllegalArgumentException("NONE review mode and evidence support must be declared together");
        }
    }

    @JsonIgnore
    @Schema(hidden = true)
    public boolean canAttemptAutomatedReview() {
        return (mode == PracticeAutomatedReviewMode.LANGUAGE_MODEL
                && evidenceSufficiency == PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET);
    }
}
