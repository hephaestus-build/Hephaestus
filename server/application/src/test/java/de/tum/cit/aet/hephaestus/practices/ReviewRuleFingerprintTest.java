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
 * write it in does not. Without both claims the occasion could be dropped from the fingerprint entirely
 * and every test would stay green while every stored fingerprint went stale.
 */
class ReviewRuleFingerprintTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");

    @Test
    void shouldChangeWhenARequiredSourceChanges() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(DIFF))));
    }

    @Test
    void shouldBeStableAcrossDeclarationOrdering() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(DIFF), required(CORE))))
                .isEqualTo(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE), required(DIFF))));
    }

    /**
     * The stance is what separates "there were no comments" from "we failed to collect the comments", so
     * a rule that changed it reaches a different verdict on the same evidence.
     */
    @Test
    void shouldChangeWhenAStanceChanges() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE), required(DIFF))))
                .isNotEqualTo(fingerprintOf(definition(
                        ScmSignals.PULL_REQUEST_OPENED,
                        required(CORE),
                        new PracticeEvidenceRequirement(DIFF, EvidenceStance.CONTEXTUAL))));
    }

    @Test
    void shouldChangeWhenTheOccasionChanges() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(definition(ScmSignals.PULL_REQUEST_MERGED, required(CORE))));
    }

    /** Whether a draft occasions the review decides which work is reviewed at all. */
    @Test
    void shouldChangeWhenDraftsStartOccasioningTheReview() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isNotEqualTo(fingerprintOf(definition(
                        List.of(ScmSignals.PULL_REQUEST_OPENED),
                        List.of(required(CORE)),
                        true,
                        de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                        null)));
    }

    @Test
    void shouldChangeWhenTheSubjectPredicateChanges() {
        PracticePrecondition manifests = new PracticePrecondition(
                "the change touches no dependency manifest",
                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/pom.xml"))));
        PracticePrecondition tests = new PracticePrecondition(
                "the change touches no tests",
                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/*Test.java"))));

        assertThat(fingerprintOf(definitionWithPrecondition(manifests)))
                .isNotEqualTo(fingerprintOf(definitionWithPrecondition(tests)));
    }

    @Test
    void shouldChangeWhenThePersonJudgedChanges() {
        PracticeDefinition author = definition(ScmSignals.PULL_REQUEST_OPENED, required(DIFF));
        PracticeDefinition reviewer = definition(
                author.signals(),
                author.evidenceRequirements(),
                author.onDrafts(),
                de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.REVIEWER,
                null);
        assertThat(fingerprintOf(reviewer)).isNotEqualTo(fingerprintOf(author));
        assertThat(fingerprintOf(reviewer)).startsWith("v5:");
    }

    private static String fingerprintOf(PracticeDefinition definition) {
        return ReviewRuleFingerprint.of(
                "describe-the-change",
                "Describe the change",
                definition.signals(),
                definition.evidenceRequirements(),
                definition.onDrafts(),
                definition.subject(),
                definition.precondition(),
                "Criteria.",
                null,
                new PracticeAutomatedReviewPolicy(
                        new SourceContractVersion("1.2.0"),
                        new PracticeAutomatedReview(
                                PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                                PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                        PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                        List.of(),
                        null),
                null);
    }

    private static PracticeDefinition definition(SignalName signal, PracticeEvidenceRequirement... needs) {
        return definition(
                List.of(signal),
                List.of(needs),
                false,
                de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                null);
    }

    private static PracticeDefinition definitionWithPrecondition(PracticePrecondition subject) {
        return definition(
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                List.of(required(DIFF)),
                false,
                de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                subject);
    }

    private static PracticeDefinition definition(
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            boolean onDrafts,
            de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole subject,
            @org.jspecify.annotations.Nullable PracticePrecondition precondition) {
        return new PracticeDefinition(
                "Describe the change",
                signals,
                evidenceRequirements,
                onDrafts,
                subject,
                precondition,
                "Criteria.",
                null,
                PracticeTestEvidence.pullRequest(),
                null,
                null,
                null);
    }

    private static PracticeEvidenceRequirement required(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.REQUIRED);
    }
}
