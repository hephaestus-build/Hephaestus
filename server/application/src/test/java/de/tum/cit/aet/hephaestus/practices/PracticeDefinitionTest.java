package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The rules that need to see a practice whole: the sources sit on the bindings and the review mode sits
 * on the policy, so only the definition can enforce the evidence rules across both.
 */
class PracticeDefinitionTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    @Test
    void shouldRejectEvidenceWithoutAutomatedReview() {
        assertThatThrownBy(() -> definition(
                        none(), List.of(PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without automated review");
    }

    /** Contextual sources alone never refuse the run, so the review would deliver a verdict having read nothing. */
    @Test
    void shouldRejectAnOccasionWithNothingTheReviewMustRead() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(contextual(CORE))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one required evidence source");
        assertThatThrownBy(() -> definition(
                        languageModel(), List.of(PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one required evidence source");
    }

    @Test
    void shouldRejectASecondOccasionWithNothingTheReviewMustRead() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(
                                PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE))),
                                PracticeBinding.on(ScmSignals.PULL_REQUEST_MERGED, List.of(contextual(COMMENTS))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one required evidence source");
    }

    @Test
    void shouldRejectASignalBoundTwice() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(
                                PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(CORE))),
                                PracticeBinding.on(ScmSignals.PULL_REQUEST_OPENED, List.of(required(COMMENTS))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is bound twice");
    }

    @Test
    void shouldRefuseAPracticeThatNamesNoOccasion() {
        assertThatThrownBy(() -> definition(languageModel(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one binding");
    }

    @Test
    void shouldReadTheArtifactKindOffTheBindings() {
        PracticeDefinition definition = definition(
                languageModel(), List.of(PracticeBinding.on(ScmSignals.PULL_REQUEST_MERGED, List.of(required(CORE)))));

        assertThat(definition.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
    }

    private static PracticeDefinition definition(
            PracticeAutomatedReview automatedReview, List<PracticeBinding> bindings) {
        return new PracticeDefinition(
                "Describe the change",
                bindings,
                "Criteria.",
                null,
                new PracticeAutomatedReviewPolicy(
                        new SourceContractVersion("1.1.0"),
                        automatedReview,
                        PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                        List.of(),
                        null),
                null,
                null,
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

    private static PracticeEvidenceRequirement required(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.REQUIRED);
    }

    private static PracticeEvidenceRequirement contextual(SourceKind sourceKind) {
        return new PracticeEvidenceRequirement(sourceKind, EvidenceStance.CONTEXTUAL);
    }
}
