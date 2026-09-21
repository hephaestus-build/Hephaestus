package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class PracticeAutomatedReviewValidationTest extends BaseUnitTest {

    /**
     * The digest has to identify <em>this</em> policy. A validation carrying a digest that two different
     * policies share would let a review claim survive the change that invalidated it.
     */
    @Test
    void shouldCarryADigestThatSeparatesOnePolicyFromAnother() {
        PracticeDefinition declared = definition(requirements());
        PracticeDefinition limited = definition(limitedRequirements());

        PracticeAutomatedReviewValidation validation =
                PracticeAutomatedReviewValidation.authorDeclared("focused-review", declared);

        assertThat(validation.status()).isEqualTo(PracticeAutomatedReviewValidationStatus.AUTHOR_DECLARED);
        assertThat(validation.policyDigest())
                .isNotEqualTo(PracticeAutomatedReviewValidation.authorDeclared("focused-review", limited)
                        .policyDigest());
        assertThat(validation.reviewRuleFingerprint()).isEqualTo(declared.provenanceFingerprint("focused-review"));
    }

    @Test
    void shouldIgnoreDeveloperCopyWhenFingerprintingReviewRules() {
        PracticeDefinition original = definition(requirements());
        PracticeDefinition revised = new PracticeDefinition(
                original.name(),
                original.bindings(),
                original.criteria(),
                original.precomputeScript(),
                original.automatedReviewPolicy(),
                "Reviews reduce integration risk.",
                original.whatGoodLooksLike(),
                original.groupSlug());

        assertThat(revised.provenanceFingerprint("focused-review"))
                .isEqualTo(original.provenanceFingerprint("focused-review"));
    }

    private static PracticeAutomatedReviewPolicy requirements() {
        return requirements(List.of());
    }

    private static PracticeAutomatedReviewPolicy limitedRequirements() {
        return requirements(
                List.of(new PracticeEvidenceLimitation("RUNTIME_NOT_OBSERVED", "Runtime is out of scope.")));
    }

    private static PracticeAutomatedReviewPolicy requirements(List<PracticeEvidenceLimitation> knownLimitations) {
        return new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.1.0"),
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                knownLimitations,
                null);
    }

    private static PracticeDefinition definition(PracticeAutomatedReviewPolicy requirements) {
        return new PracticeDefinition(
                "Focused review",
                PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST),
                "Assess whether the change stays focused.",
                null,
                requirements,
                null,
                null,
                null);
    }
}
