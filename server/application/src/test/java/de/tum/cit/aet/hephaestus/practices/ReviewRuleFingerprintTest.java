package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

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
    void shouldPreserveAssessmentWhenTheOccasionChanges() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isEqualTo(fingerprintOf(definition(ScmSignals.PULL_REQUEST_MERGED, required(CORE))));
    }

    @Test
    void shouldPreserveAssessmentWhenReviewStateSelectionChanges() {
        assertThat(fingerprintOf(definition(ScmSignals.PULL_REQUEST_OPENED, required(CORE))))
                .isEqualTo(fingerprintOf(definition(
                        List.of(ScmSignals.PULL_REQUEST_OPENED),
                        List.of(required(CORE)),
                        Map.of("draftStatus", Set.of("DRAFT")),
                        ActorRole.AUTHOR,
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
                author.signals(), author.evidenceRequirements(), author.reviewWhen(), ActorRole.REVIEWER, null);
        assertThat(fingerprintOf(reviewer)).isNotEqualTo(fingerprintOf(author));
        assertThat(fingerprintOf(reviewer)).startsWith("v5:");
    }

    @Test
    void changingTheWorkTypeChangesAssessmentEvenWithoutEvidenceRequirements() {
        var policy = new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.2.0"),
                new PracticeAutomatedReview(PracticeAutomatedReviewMode.NONE, PracticeEvidenceSufficiency.NONE),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null);
        var pullRequest = new PracticeDefinition(
                "Describe the work",
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                List.of(),
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Criteria.",
                null,
                policy,
                null,
                null,
                null);
        var issue = new PracticeDefinition(
                "Describe the work",
                List.of(ScmSignals.ISSUE_OPENED),
                List.of(),
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Criteria.",
                null,
                policy,
                null,
                null,
                null);
        assertThat(pullRequest.provenanceFingerprint("describe-work"))
                .isNotEqualTo(issue.provenanceFingerprint("describe-work"));
    }

    private static String fingerprintOf(PracticeDefinition definition) {
        return ReviewRuleFingerprint.of(
                "describe-the-change",
                "Describe the change",
                definition.artifactKind(),
                definition.evidenceRequirements(),
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
        return definition(List.of(signal), List.of(needs), Map.of(), ActorRole.AUTHOR, null);
    }

    private static PracticeDefinition definitionWithPrecondition(PracticePrecondition subject) {
        return definition(
                List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(required(DIFF)), Map.of(), ActorRole.AUTHOR, subject);
    }

    private static PracticeDefinition definition(
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements,
            Map<String, Set<String>> reviewWhen,
            ActorRole subject,
            @Nullable PracticePrecondition precondition) {
        return new PracticeDefinition(
                "Describe the change",
                signals,
                evidenceRequirements,
                reviewWhen,
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
