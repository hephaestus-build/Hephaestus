package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The digest of the review frame (contract version, review mode, insufficient-evidence handling, and
 * limitations) — not of what a review reads, which lives on the definition and is digested by
 * {@code ReviewRuleFingerprint}.
 */
class PracticeAutomatedReviewPolicyDigestTest extends BaseUnitTest {

    private static final String PINNED_DIGEST = "ab525cda62e5d557e6c65899d711e8375572957f03c0d1ba220b632ae37086c6";

    @Test
    void shouldPinACanonicalDigestForAKnownPolicy() {
        // Earlier releases' stored fingerprints are compared against this value, so it is a wire
        // format: reordering or reframing its fields silently invalidates every one, undetected by the
        // other tests here since they only compare digests to each other.
        String digest = PracticeAutomatedReviewPolicyDigest.digest(policy());

        assertThat(digest).isEqualTo(PINNED_DIGEST);
    }

    /**
     * A limitation is a claim the practice will never make, so a review run under a policy that added
     * one is not the same review as one run before it.
     */
    @Test
    void shouldChangeWhenAKnownLimitationChanges() {
        assertThat(PracticeAutomatedReviewPolicyDigest.digest(policy()))
                .isNotEqualTo(PracticeAutomatedReviewPolicyDigest.digest(new PracticeAutomatedReviewPolicy(
                        new SourceContractVersion("1.0.0"),
                        capability(
                                PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                                PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                        PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                        List.of(new PracticeEvidenceLimitation("RUNTIME_NOT_OBSERVED", "Something else entirely.")),
                        null)));
    }

    private static PracticeAutomatedReviewPolicy policy() {
        return new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.0.0"),
                capability(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(new PracticeEvidenceLimitation("RUNTIME_NOT_OBSERVED", "Runtime behavior is outside scope.")),
                null);
    }

    private static PracticeAutomatedReview capability(
            PracticeAutomatedReviewMode method, PracticeEvidenceSufficiency coverage) {
        return new PracticeAutomatedReview(method, coverage);
    }
}
