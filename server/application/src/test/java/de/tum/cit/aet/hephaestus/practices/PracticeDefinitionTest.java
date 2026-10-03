package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PracticeDefinitionTest extends BaseUnitTest {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");

    @Test
    void shouldRejectEvidenceWithoutAutomatedReview() {
        assertThatThrownBy(() -> definition(none(), List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(required(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without automated review");
    }

    /** Contextual sources alone never refuse the run, so the review would deliver a verdict having read nothing. */
    @Test
    void shouldRejectAnOccasionWithNothingTheReviewMustRead() {
        assertThatThrownBy(() ->
                        definition(languageModel(), List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(contextual(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one required evidence source");
        assertThatThrownBy(() -> definition(languageModel(), List.of(ScmSignals.PULL_REQUEST_OPENED), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one required evidence source");
    }

    @Test
    void shouldRejectASignalBoundTwice() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_OPENED),
                        List.of(required(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("You chose the same moment twice. Choose each moment once.");
    }

    @Test
    void shouldRefuseAPracticeThatNamesNoOccasion() {
        assertThatThrownBy(() -> definition(languageModel(), List.of(), List.of(required(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose at least one moment that starts a review.");
    }

    @Test
    void shouldReadTheArtifactKindOffTheSignals() {
        PracticeDefinition definition =
                definition(languageModel(), List.of(ScmSignals.PULL_REQUEST_MERGED), List.of(required(CORE)));

        assertThat(definition.artifactKind()).isEqualTo(ArtifactKinds.PULL_REQUEST);
    }

    @Test
    void shouldRejectSignalsFromDifferentWorkTypes() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.ISSUE_OPENED),
                        List.of(required(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one kind of work");
    }

    @Test
    void shouldRejectDuplicateEvidenceSources() {
        assertThatThrownBy(() -> definition(
                        languageModel(),
                        List.of(ScmSignals.PULL_REQUEST_OPENED),
                        List.of(required(CORE), contextual(CORE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lists an evidence source twice");
    }

    @Test
    void shouldCanonicalizeTheOccasionWithoutChangingItsMeaning() {
        var definition = definition(
                languageModel(),
                List.of(ScmSignals.PULL_REQUEST_REVIEWED, ScmSignals.PULL_REQUEST_OPENED),
                List.of(required(new SourceKind("scm.pull-request.diff")), required(CORE)));
        assertThat(definition.signals())
                .containsExactly(ScmSignals.PULL_REQUEST_OPENED, ScmSignals.PULL_REQUEST_REVIEWED);
        assertThat(definition.evidenceRequirements())
                .extracting(PracticeEvidenceRequirement::sourceKind)
                .containsExactly(CORE, new SourceKind("scm.pull-request.diff"));
    }

    @Test
    void shouldRoundTripTheFlatDefinitionAndRejectRemovedFields() throws Exception {
        var mapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        var original = definition(languageModel(), List.of(ScmSignals.PULL_REQUEST_OPENED), List.of(required(CORE)));
        String json = mapper.writeValueAsString(original);
        assertThat(json)
                .contains("\"signals\"", "\"evidenceRequirements\"", "\"precondition\"")
                .doesNotContain("bindings", "appliesWhen");
        assertThat(mapper.readValue(json, PracticeDefinition.class)).isEqualTo(original);
        var withRemovedField = mapper.readTree(json).deepCopy();
        ((ObjectNode) withRemovedField).putArray("bindings");
        assertThatThrownBy(() -> mapper.treeToValue(withRemovedField, PracticeDefinition.class))
                .isInstanceOf(DatabindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("Unknown practice definition field: bindings");
    }

    private static PracticeDefinition definition(
            PracticeAutomatedReview automatedReview,
            List<SignalName> signals,
            List<PracticeEvidenceRequirement> evidenceRequirements) {
        return new PracticeDefinition(
                "Describe the change",
                signals,
                evidenceRequirements,
                Map.of(),
                ActorRole.AUTHOR,
                null,
                "Criteria.",
                null,
                new PracticeAutomatedReviewPolicy(
                        new SourceContractVersion("1.2.0"),
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
