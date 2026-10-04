package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SubjectEvidenceCollection;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the validator checks that the definition record cannot check for itself: agreement with the
 * registered domains and with the source catalog.
 */
class PracticeDefinitionValidatorTest extends BaseUnitTest {

    private static final SourceContractVersion VERSION = new SourceContractVersion("1.2.0");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind FOR_ANOTHER_KIND = new SourceKind("scm.issue.core");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final PracticeDefinitionValidator validator = new PracticeDefinitionValidator(
            new ClasspathArtifactSourceCatalogRegistry(mapper, java.time.Clock.systemUTC()),
            PracticeSignalOptionsFixture.real());

    /**
     * The artifact kind is derived from the signal's prefix, so a misspelled signal would otherwise
     * invent a kind nothing can raise and leave the practice looking configured but never firing.
     */
    @Test
    void rejectsASignalNoRegisteredDomainDeclares() {
        assertThatThrownBy(() -> validator.validate(definition(
                        SignalName.of("scm.pull_request.rebased"), null, List.of(need(DIFF)), languageModel())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("One of the chosen moments is not one this kind of work offers. "
                        + "Choose from the moments listed for it.")
                .satisfies(PracticeDefinitionValidatorTest::namesNoIdentifier);
    }

    @Test
    void rejectsUnknownEvidenceSource() {
        assertThatThrownBy(() -> validator.validate(definition(
                        ScmSignals.PULL_REQUEST_OPENED,
                        null,
                        List.of(need(new SourceKind("scm.pull-request.unknown"))),
                        languageModel())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scm.pull-request.unknown is not an evidence source Hephaestus knows. Choose from the "
                        + "sources listed under “Reads” in “When this practice is reviewed”.");
    }

    @Test
    void rejectsAPersonTheWorkTypeDoesNotRecordInTheEditorsOwnLabel() {
        assertThatThrownBy(() -> validator.validate(new PracticeDefinition(
                        "Focused review",
                        List.of(ScmSignals.ISSUE_OPENED),
                        List.of(need(new SourceKind("scm.issue.core"))),
                        Map.of(),
                        de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.MERGER,
                        null,
                        "Assess the review",
                        PracticeJudgment.holistic(),
                        null,
                        languageModel(),
                        null,
                        null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This kind of work does not record “Whoever merged it”, so a review of it cannot be about "
                        + "them. Choose from the people listed under “Person this practice judges”.")
                .satisfies(PracticeDefinitionValidatorTest::namesNoIdentifier);
    }

    @Test
    void rejectsEvidenceThatCannotExistForTheReviewedKind() {
        assertThatThrownBy(() -> validator.validate(definition(
                        ScmSignals.PULL_REQUEST_OPENED, null, List.of(need(FOR_ANOTHER_KIND)), languageModel())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("“Issue details” is not available for this kind of work. Turn it off, or choose "
                        + "evidence this kind of work has.")
                .satisfies(PracticeDefinitionValidatorTest::namesNoIdentifier);
    }

    /** Several signals on the one occasion stay legal: that is how a practice judged all along is written. */
    @Test
    void acceptsSeveralSignalsOnTheOneOccasion() {
        PracticeDefinition definition = new PracticeDefinition(
                "Focused review",
                List.of(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_MERGED),
                List.of(need(DIFF)),
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Assess the review",
                PracticeJudgment.holistic(),
                null,
                languageModel(),
                null,
                null,
                null);

        assertThatCode(() -> validator.validate(definition)).doesNotThrowAnyException();
    }

    /**
     * Binding to the hand-request signal decides nothing — the gate matches such a request by artifact
     * kind and ignores the signal — so a practice holding only it would look configured and never fire.
     */
    @Test
    void rejectsBindingToAReviewSomebodyAsksForByHand() {
        assertThatThrownBy(() -> validator.validate(
                        definition(ScmSignals.PULL_REQUEST_MANUAL_REVIEW, null, List.of(need(DIFF)), languageModel())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Remove “Review requested by hand”.")
                .hasMessageContaining("not a moment to choose")
                .satisfies(PracticeDefinitionValidatorTest::namesNoIdentifier);
    }

    /**
     * An exhaustive claim over a source that can never report a complete capture is a practice that
     * refuses every review it triggers, indistinguishable in its report from nobody having done the
     * thing yet.
     */
    @Test
    void rejectsAnAbsenceClaimOverASourceThatIsNeverComplete() {
        assertThatThrownBy(() -> validator.validate(definition(
                        ScmSignals.PULL_REQUEST_OPENED,
                        null,
                        List.of(
                                need(DIFF),
                                new PracticeEvidenceRequirement(
                                        new SourceKind("scm.linked-work-items"), EvidenceStance.EXHAUSTIVE)),
                        languageModel())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("“Linked work items” can never be captured completely")
                .satisfies(PracticeDefinitionValidatorTest::namesNoIdentifier);
    }

    @Test
    void acceptsAnAbsenceClaimOverASourceThatCanBeComplete() {
        assertThatCode(() -> validator.validate(definition(
                        ScmSignals.PULL_REQUEST_OPENED,
                        null,
                        List.of(new PracticeEvidenceRequirement(
                                new SourceKind("scm.review-threads"), EvidenceStance.EXHAUSTIVE)),
                        languageModel())))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsGuidanceOnlyPracticeWithoutAutomatedInputs() {
        assertThatCode(() -> validator.validate(
                        definition(ScmSignals.PULL_REQUEST_OPENED, null, List.of(), withoutAutomatedReview())))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHumanReviewPracticeThatStillSaysWhatItIsAbout() {
        assertThatCode(() -> validator.validate(
                        definition(ScmSignals.PULL_REQUEST_OPENED, null, List.of(need(DIFF)), humanReview())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPrecomputeScriptWithoutAutomatedReview() {
        assertThatThrownBy(() -> validator.validate(definition(
                        ScmSignals.PULL_REQUEST_OPENED, "export default {}", List.of(), withoutAutomatedReview())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A practice Hephaestus cannot review cannot define a precompute script");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PRESENT", "NOT_MET", "BAD", "ASSESSED", "NOT_APPLICABLE", "UNDETERMINED"})
    void rejectsDetectorVocabularyInDeveloperFacingGuidance(String label) {
        PracticeDefinition definition = new PracticeDefinition(
                "Focused review",
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                List.of(need(DIFF)),
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Assess the review",
                PracticeJudgment.holistic(),
                null,
                languageModel(),
                "A description that is " + label + " tells a reviewer nothing.",
                null,
                null);

        assertThatThrownBy(() -> validator.validate(definition))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Why it matters is guidance for people. Remove the review result label “" + label
                        + "” and say it in plain words.");
    }

    /**
     * The invariant that keeps the validator's "can never be captured completely" rule from ever needing
     * to fire, asserted over the whole vocabulary rather than over one declaration.
     *
     * <p>A collection whose source can only ever be PARTIAL — retrieval that cannot establish it found
     * everything, such as referenced wiki documents or resolved work-item links — would be undecidable at
     * review time and therefore fall through to running the practice, safely and for ever. That is not a
     * bug, it is the guard rail; but a clause that can never fire reads in the catalogue as though it
     * narrowed something, so the vocabulary must not offer one.
     */
    @Test
    void everyNamedCollectionCanBeCapturedWholeAndSoCanActuallyDecideAClause() {
        var catalogs = new ClasspathArtifactSourceCatalogRegistry(mapper, java.time.Clock.systemUTC());

        for (SubjectEvidenceCollection collection : SubjectEvidenceCollection.values()) {
            assertThat(catalogs.requireSource(VERSION, collection.sourceKind())
                            .completenessPolicy()
                            .supportsComplete())
                    .as(
                            "%s is read from %s, which can never report a complete capture",
                            collection, collection.sourceKind())
                    .isTrue();
        }
    }

    @Test
    void rejectsDiffPreconditionsForIssues() {
        assertThatThrownBy(() -> validator.validate(new PracticeDefinition(
                        "Focused review",
                        List.of(ScmSignals.ISSUE_OPENED),
                        List.of(need(new SourceKind("scm.issue.core"))),
                        Map.of(),
                        de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                        new PracticePrecondition(
                                "the change touches no dependency manifest",
                                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/pom.xml")))),
                        "Assess the review",
                        PracticeJudgment.holistic(),
                        null,
                        languageModel(),
                        null,
                        null,
                        null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unsupported precondition for this work type: CHANGED_PATH");
    }

    @Test
    void acceptsASubjectDecidableFromASourceTheWorkTypeCapturesWhole() {
        assertThatCode(() -> validator.validate(withSubject(
                        new PracticePrecondition(
                                "the change touches no dependency manifest or lockfile",
                                List.of(PracticePreconditionClause.changedPathMatches(
                                        List.of("**/pom.xml", "**/package.json")))),
                        DIFF)))
                .doesNotThrowAnyException();
    }

    private static PracticeDefinition withSubject(PracticePrecondition subject, SourceKind reads) {
        return new PracticeDefinition(
                "Focused review",
                List.of(ScmSignals.PULL_REQUEST_OPENED),
                List.of(need(reads)),
                Map.of(),
                de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole.AUTHOR,
                subject,
                "Assess the review",
                PracticeJudgment.holistic(),
                null,
                languageModel(),
                null,
                null,
                null);
    }

    @Test
    void rejectsUnsupportedStateDimensionsAndValuesBeforeAReviewCanBeScheduled() {
        var original = definition(ScmSignals.PULL_REQUEST_OPENED, null, List.of(need(DIFF)), languageModel());
        for (var policy : List.of(Map.of("draft", Set.of("NOT_DRAFT")), Map.of("state", Set.of("PUBLISHED")))) {
            var authored = new PracticeDefinition(
                    original.name(),
                    original.signals(),
                    original.evidenceRequirements(),
                    policy,
                    original.subject(),
                    original.precondition(),
                    original.criteria(),
                    PracticeJudgment.holistic(),
                    original.precomputeScript(),
                    original.automatedReviewPolicy(),
                    original.whyItMatters(),
                    original.whatGoodLooksLike(),
                    original.groupSlug());
            assertThatThrownBy(() -> validator.validate(authored))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported review state");
        }
    }

    private static PracticeDefinition definition(
            SignalName signal,
            @Nullable String precomputeScript,
            List<PracticeEvidenceRequirement> needs,
            PracticeAutomatedReviewPolicy policy) {
        return new PracticeDefinition(
                "Focused review",
                List.of(signal),
                needs,
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Assess the review",
                policy.automatedReview().mode() == PracticeAutomatedReviewMode.NONE
                        ? null
                        : PracticeJudgment.holistic(),
                precomputeScript,
                policy,
                null,
                null,
                null);
    }

    private static PracticeEvidenceRequirement need(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.REQUIRED);
    }

    private static PracticeAutomatedReviewPolicy languageModel() {
        return new PracticeAutomatedReviewPolicy(
                VERSION,
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null);
    }

    private static PracticeAutomatedReviewPolicy withoutAutomatedReview() {
        return new PracticeAutomatedReviewPolicy(
                VERSION,
                new PracticeAutomatedReview(PracticeAutomatedReviewMode.NONE, PracticeEvidenceSufficiency.NONE),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null);
    }

    private static PracticeAutomatedReviewPolicy humanReview() {
        return new PracticeAutomatedReviewPolicy(
                VERSION,
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                new PracticeEvidenceLimitation("HUMAN_CONTEXT", "A person must review this practice."));
    }

    /** A message the practice editor shows names sources, moments and roles in words, never by identifier. */
    private static void namesNoIdentifier(Throwable thrown) {
        assertThat(thrown.getMessage()).doesNotContain("scm.", "artifact", "signal", "binding", "_");
    }
}
