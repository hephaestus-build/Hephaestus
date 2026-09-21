package de.tum.cit.aet.hephaestus.practices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class PracticeEvidenceDefaultsTest {

    @ParameterizedTest
    @MethodSource("baselines")
    void shouldCreateTheArtifactBaseline(ArtifactKind artifact, List<String> required) {
        // Declaration order, not alphabetical — this is the list an authoring UI shows first. Sorting is
        // the binding's business, since that list is what the fingerprint is taken over.
        JsonMapper mapper = JsonMapper.builder().build();
        var catalogs = new ClasspathArtifactSourceCatalogRegistry(mapper, java.time.Clock.systemUTC());
        var defaults = new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog());

        assertThat(defaults.needsFor(artifact))
                .extracting(item -> item.sourceKind().value())
                .containsExactlyElementsOf(required);
        assertThat(defaults.needsFor(artifact))
                .as("a default a review may start on: every source it names is one the review must read")
                .allMatch(PracticeEvidenceRequirement::refuses);

        PracticeAutomatedReviewPolicy policy = defaults.policyFor(artifact);
        assertThat(policy.sourceContractVersion()).isEqualTo(new SourceContractVersion("1.1.0"));
        assertThat(policy.whenEvidenceIsInsufficient())
                .isEqualTo(PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW);
        assertThat(policy.knownLimitations()).isNotEmpty();
    }

    @Test
    void shouldRefuseAKindItHasNoDefaultFor() {
        JsonMapper mapper = JsonMapper.builder().build();
        var defaults = new PracticeEvidenceDefaults(
                new ClasspathArtifactSourceCatalogRegistry(mapper, java.time.Clock.systemUTC()),
                PracticeSignalOptionsFixture.catalog());

        // Spelled like a plausible future domain, not gibberish: the case that matters is a kind real to
        // the person writing it and unknown to this build.
        ArtifactKind undeclared = ArtifactKind.of("scm.deployment");

        assertThatThrownBy(() -> defaults.needsFor(undeclared)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> defaults.policyFor(undeclared)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> baselines() {
        return Stream.of(
                // REQUIRED, not contextual: keeps "there were no comments" distinguishable from "we failed
                // to collect the comments".
                Arguments.of(
                        ArtifactKinds.PULL_REQUEST,
                        List.of("scm.pull-request.core", "scm.pull-request.diff", "scm.pull-request.comments")),
                Arguments.of(ArtifactKinds.ISSUE, List.of("scm.issue.core", "scm.issue.comments")),
                Arguments.of(ArtifactKinds.CONVERSATION_THREAD, List.of("slack.conversation.thread")),
                // A kind this module knows nothing about, reaching a baseline via the source contract's own
                // defaultRequirement — the whole claim of the contract.
                Arguments.of(ArtifactKind.of("docs.document"), List.of("docs.document.core")));
    }
}
