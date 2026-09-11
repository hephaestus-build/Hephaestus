package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The review frame: what a practice claims about its own automated review. What a review <em>reads</em>
 * is stated per occasion on the bindings, so the rules relating evidence to the review mode are enforced
 * where both are visible — on the definition — rather than here.
 */
class PracticeAutomatedReviewPolicyTest extends BaseUnitTest {

    private static final SourceContractVersion VERSION = new SourceContractVersion("1.1.0");

    /**
     * A limitation is what the evidence cannot show. A practice with no automated review shows nothing,
     * so a limitation on it is a claim about a review that never runs.
     */
    @Test
    void shouldRejectLimitationsWithoutAutomatedReview() {
        assertThatThrownBy(() -> policy(none(), List.of(new PracticeEvidenceLimitation("UNSUPPORTED_CLAIM", "Claim."))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without automated review");
    }

    @Test
    void shouldRejectDuplicateLimitationCodesAndInsufficientEvidenceWithNoReason() {
        PracticeEvidenceLimitation limitation =
                new PracticeEvidenceLimitation("MISSING_CONTEXT", "Context is missing.");
        assertThatThrownBy(() -> policy(languageModel(), List.of(limitation, limitation)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("codes must be unique");
        PracticeAutomatedReview additionalContext = new PracticeAutomatedReview(
                PracticeAutomatedReviewMode.LANGUAGE_MODEL, PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT);
        assertThatThrownBy(() -> policy(additionalContext, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must state why a human is needed");
    }

    /**
     * Limitations are digested into the review-rule fingerprint, so two authors writing the same two
     * limitations in a different order must not read as two different rules.
     */
    @Test
    void shouldCanonicalizeLimitationOrder() {
        PracticeAutomatedReviewPolicy policy = policy(
                languageModel(),
                List.of(
                        new PracticeEvidenceLimitation("SECOND_LIMITATION", "Second."),
                        new PracticeEvidenceLimitation("FIRST_LIMITATION", "First.")));

        assertThat(policy.knownLimitations())
                .extracting(PracticeEvidenceLimitation::code)
                .containsExactly("FIRST_LIMITATION", "SECOND_LIMITATION");
    }

    private static PracticeAutomatedReviewPolicy policy(
            PracticeAutomatedReview automatedReview, List<PracticeEvidenceLimitation> knownLimitations) {
        return new PracticeAutomatedReviewPolicy(
                VERSION,
                automatedReview,
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                knownLimitations,
                null);
    }

    private static PracticeAutomatedReview languageModel() {
        return new PracticeAutomatedReview(
                PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET);
    }

    private static PracticeAutomatedReview none() {
        return new PracticeAutomatedReview(PracticeAutomatedReviewMode.NONE, PracticeEvidenceSufficiency.NONE);
    }
}
