package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What counts as the same review rule, the occasion and its evidence included.
 *
 * <p>Changing what a review must read makes it a different review; the order the author happened to
 * write it in does not. Without both claims the bindings could be dropped from the fingerprint entirely
 * and every test would stay green while every stored fingerprint went stale.
 */
class ReviewRuleFingerprintTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");

    @Test
    void shouldChangeWhenARequiredSourceChanges() {
        assertThat(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(DIFF))));
    }

    @Test
    void shouldBeStableAcrossDeclarationOrdering() {
        assertThat(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(DIFF), required(CORE))))
                .isEqualTo(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(CORE), required(DIFF))));
    }

    /**
     * The stance is what separates "there were no comments" from "we failed to collect the comments", so
     * a rule that changed it reaches a different verdict on the same evidence.
     */
    @Test
    void shouldChangeWhenAStanceChanges() {
        assertThat(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(CORE), required(DIFF))))
                .isNotEqualTo(fingerprintOf(binding(
                        ScmSignals.PULL_REQUEST_OPENED,
                        required(CORE),
                        new PracticeEvidenceRequirement(DIFF, EvidenceStance.CONTEXTUAL))));
    }

    @Test
    void shouldChangeWhenTheOccasionChanges() {
        assertThat(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(binding(ScmSignals.PULL_REQUEST_MERGED, required(CORE))));
    }

    /** Whether a draft occasions the review decides which work is reviewed at all. */
    @Test
    void shouldChangeWhenDraftsStartOccasioningTheReview() {
        assertThat(fingerprintOf(binding(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(
                        new PracticeBinding(List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(required(CORE)), true)));
    }

    @Test
    void shouldChangeWhenTheSubjectPredicateChanges() {
        PracticeSubject manifests = new PracticeSubject(
                "the change touches no dependency manifest",
                List.of(PracticeSubjectClause.changedPathMatches(List.of("**/pom.xml"))));
        PracticeSubject tests = new PracticeSubject(
                "the change touches no tests",
                List.of(PracticeSubjectClause.changedPathMatches(List.of("**/*Test.java"))));

        assertThat(fingerprintOf(bindingWithSubject(manifests))).isNotEqualTo(fingerprintOf(bindingWithSubject(tests)));
    }

    private static String fingerprintOf(PracticeBinding binding) {
        return ReviewRuleFingerprint.of(
                "describe-the-change",
                "Describe the change",
                List.of(binding),
                "Criteria.",
                null,
                new PracticeAutomatedReviewPolicy(
                        new SourceContractVersion("1.1.0"),
                        new PracticeAutomatedReview(
                                PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                                PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                        PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                        List.of(),
                        null),
                null);
    }

    private static PracticeBinding binding(SignalName signal, PracticeEvidenceRequirement... needs) {
        return PracticeBinding.on(signal, List.of(needs));
    }

    private static PracticeBinding bindingWithSubject(PracticeSubject subject) {
        return new PracticeBinding(
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                List.of(required(DIFF)),
                false,
                de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                subject);
    }

    private static PracticeEvidenceRequirement required(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.REQUIRED);
    }
}
