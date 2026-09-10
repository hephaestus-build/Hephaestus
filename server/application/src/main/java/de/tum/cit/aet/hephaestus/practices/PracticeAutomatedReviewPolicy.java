package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * What a practice's automated review is, minus what it reads.
 *
 * <p>The frame the review runs in: which contract version names the sources, whether a language model
 * runs at all, and the claims the evidence cannot support whatever the occasion. Which sources it
 * actually reads is <em>not</em> here — that depends on what occasioned the review, so it lives on
 * {@link PracticeBinding#needs()}.
 */
@Schema(description = "Author-defined automated review settings for one practice revision")
public record PracticeAutomatedReviewPolicy(
        @NonNull
        @NotNull
        @Schema(description = "Exact contract version that defines source kinds and source-state semantics")
        SourceContractVersion sourceContractVersion,

        @NonNull
        @NotNull
        @Valid
        @Schema(description = "Automated review configuration; human review is a separate process")
        PracticeAutomatedReview automatedReview,

        @NonNull @NotNull @Schema(description = "Conservative action when required evidence does not pass")
        PracticeInsufficientEvidenceAction whenEvidenceIsInsufficient,

        @NonNull
        @NotNull
        @Schema(description = "Claims the selected evidence cannot support even when every requirement passes")
        List<@Valid PracticeEvidenceLimitation> knownLimitations,

        @Nullable
        @Valid
        @Schema(
                description = "Why this practice needs a human rather than automated review; present only when "
                        + "evidenceSufficiency is DECLARED_EVIDENCE_INSUFFICIENT")
        PracticeEvidenceLimitation insufficiencyReason) {
    public PracticeAutomatedReviewPolicy {
        Objects.requireNonNull(sourceContractVersion, "sourceContractVersion");
        Objects.requireNonNull(automatedReview, "automatedReview");
        knownLimitations = Objects.requireNonNull(knownLimitations, "knownLimitations").stream()
                .sorted(Comparator.comparing(PracticeEvidenceLimitation::code))
                .toList();
        if (automatedReview.mode() == PracticeAutomatedReviewMode.NONE && !knownLimitations.isEmpty()) {
            throw new IllegalArgumentException("A practice without automated review cannot declare limitations");
        }
        Objects.requireNonNull(whenEvidenceIsInsufficient, "whenEvidenceIsInsufficient");
        Set<String> limitationCodes = new HashSet<>();
        if (knownLimitations.stream()
                .map(PracticeEvidenceLimitation::code)
                .anyMatch(code -> !limitationCodes.add(code))) {
            throw new IllegalArgumentException("Known limitation codes must be unique");
        }
        // Its own field, not another known limitation: a limitation says what the evidence cannot show,
        // this says why no automated review runs at all.
        boolean declaredInsufficient =
                automatedReview.evidenceSufficiency() == PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT;
        if (declaredInsufficient && insufficiencyReason == null) {
            throw new IllegalArgumentException("Insufficient evidence must state why a human is needed");
        }
        if (!declaredInsufficient && insufficiencyReason != null) {
            throw new IllegalArgumentException("Only insufficient evidence carries a reason a human is needed");
        }
    }
}
