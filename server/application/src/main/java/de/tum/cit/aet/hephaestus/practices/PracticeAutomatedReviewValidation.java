package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

@Schema(description = "Who stands behind a practice's automated-review policy, and which exact policy")
public record PracticeAutomatedReviewValidation(
        @NonNull
        @Schema(
                description =
                        "Validation lifecycle; authors cannot mark their own review policy as independently validated")
        PracticeAutomatedReviewValidationStatus status,

        @NonNull @Schema(description = "Source contract the declared practice definition is written against")
        SourceContractVersion sourceContractVersion,

        @NonNull @Schema(description = "SHA-256 digest of the exact automated-review policy")
        String policyDigest,

        @NonNull @Schema(description = "Versioned fingerprint of the exact review rules")
        String reviewRuleFingerprint) {
    public PracticeAutomatedReviewValidation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sourceContractVersion, "sourceContractVersion");
        Objects.requireNonNull(policyDigest, "policyDigest");
        Objects.requireNonNull(reviewRuleFingerprint, "reviewRuleFingerprint");
        if (!policyDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid policy digest");
        }
        if (!reviewRuleFingerprint.matches("v[0-9]+:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid review-rule fingerprint");
        }
    }

    public static PracticeAutomatedReviewValidation authorDeclared(String practiceSlug, PracticeDefinition definition) {
        Objects.requireNonNull(practiceSlug, "practiceSlug");
        Objects.requireNonNull(definition, "definition");
        PracticeAutomatedReviewPolicy requirements = definition.automatedReviewPolicy();
        return new PracticeAutomatedReviewValidation(
                PracticeAutomatedReviewValidationStatus.AUTHOR_DECLARED,
                requirements.sourceContractVersion(),
                PracticeAutomatedReviewPolicyDigest.digest(requirements),
                definition.provenanceFingerprint(practiceSlug));
    }
}
