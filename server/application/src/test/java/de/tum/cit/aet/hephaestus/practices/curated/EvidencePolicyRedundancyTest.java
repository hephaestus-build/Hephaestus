package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Facts the shipped evidence vocabulary asserts about itself, held as tests so a source added later
 * cannot silently invalidate them. A failure here means deciding whether the new case is a genuine
 * requirement to reintroduce deliberately, or an authoring slip just caught — not relaxing the test.
 */
class EvidencePolicyRedundancyTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final ClasspathArtifactSourceCatalogRegistry registry =
            new ClasspathArtifactSourceCatalogRegistry(objectMapper, Clock.systemUTC());
    private final PracticeSignalOptions signalOptions = PracticeSignalOptionsFixture.real();
    private final BundledPracticeCatalogLoader loader = new BundledPracticeCatalogLoader(
            objectMapper,
            new PracticeDefinitionValidator(registry, signalOptions),
            new PracticeEvidenceDefaults(registry, PracticeSignalOptionsFixture.catalog()));

    /**
     * Every kind a practice can be authored against has at least one source that declares it applies —
     * checked at build time, because the alternative is discovering it at review time, unwatched.
     */
    @Test
    void everyAuthorableArtifactKindHasEvidenceThatAppliesToIt() {
        for (ArtifactKind kind : signalOptions.authorableKinds()) {
            assertThat(registry.current().sourcesFor(kind.value()))
                    .as(
                            "artifact kind '%s' can be authored against but no source declares it applies; a practice "
                                    + "written on it would refuse every review it triggered",
                            kind)
                    .isNotEmpty();
        }
    }

    /**
     * Which shipped practices claim something is <em>absent</em>, recorded here because it is a reading of
     * each practice's criteria rather than anything the code can derive. Adding to this list is a decision
     * about what a practice may assert, made here rather than noticed in a diff.
     *
     * <p>Two different claims land a practice on this list, and both are absences.
     *
     * <p>The first four assert a <em>gap</em> over a corpus that arrives in pages: "no reviewer raised this",
     * "the issue closed with its outcome unmet". A partial capture of review threads is equally consistent
     * with "nobody raised it" and "the raising was in the part we did not fetch", so the whole capture is
     * what makes the gap assertable at all.
     *
     * <p>The eight defect detectors are here for the mirror-image reason: to assert a <em>clean</em> result.
     * Their target signal is the undesirable behaviour, so their strength has the shape "the defect could have
     * appeared in this change and did not" — a universal over the corpus, admissible only where the corpus is
     * closed and was covered whole. Each was already scoped to the added and changed lines of the diff, and
     * {@code scm.pull-request.diff} is a source the contract can only report {@code COMPLETE} (it does not
     * support {@code PARTIAL}) and already demands {@code COMPLETE_AND_NON_EMPTY}, so holding it
     * {@code EXHAUSTIVE} costs no readiness and buys the verdict. Without it these practices had to answer a
     * clean surface with {@code NOT_APPLICABLE} — "this work had no subject for this practice" — which is
     * false of a change they read, and which reads to a developer as "you touched nothing relevant".
     */
    @Test
    void onlyThePracticesThatAssertAnAbsenceDemandAWholeCapture() {
        Map<String, Set<SourceKind>> exhaustive = new LinkedHashMap<>();
        loader.catalog()
                .practices()
                .forEach(practice -> practice.definition()
                        .bindings()
                        .forEach(binding -> binding.needs().stream()
                                .filter(need -> need.stance() == EvidenceStance.EXHAUSTIVE)
                                .forEach(need -> exhaustive
                                        .computeIfAbsent(practice.slug(), slug -> new LinkedHashSet<>())
                                        .add(need.sourceKind()))));

        assertThat(exhaustive)
                .containsOnlyKeys(
                        // Gap-shaped absences over a paginated corpus.
                        "merged-past-unresolved-review-threads",
                        "engaging-with-inline-review-comments",
                        "issue-closed-with-unmet-outcome",
                        "ready-and-traceable-handoff",
                        // Positive absence claims over the complete changed-file diff.
                        "keeps-the-test-suite-honest",
                        "change-keeps-linked-docs-consistent",
                        "excludes-generated-and-build-artifacts",
                        "removes-duplication-instead-of-copy-pasting",
                        "keeps-functions-small-and-single-purpose",
                        "leaves-the-code-clean-with-intent-revealing-comments",
                        "handles-errors-instead-of-swallowing-them",
                        "validates-inputs-and-edge-cases-at-the-boundary",
                        "avoids-unsafe-panics-and-chosen-crashes",
                        "validates-and-escapes-untrusted-input",
                        "avoids-insecure-defaults-and-over-broad-permissions");
        assertThat(exhaustive.get("merged-past-unresolved-review-threads"))
                .containsExactly(new SourceKind("scm.review-threads"));
        assertThat(exhaustive.get("issue-closed-with-unmet-outcome"))
                .containsExactly(new SourceKind("scm.issue.comments"));
        // A defect detector bounds the diff and nothing else: its clean verdict must not silently start
        // ranging over the repository tree, which no capture can ever cover whole.
        assertThat(exhaustive.get("handles-errors-instead-of-swallowing-them"))
                .containsExactly(new SourceKind("scm.pull-request.diff"));
        assertThat(exhaustive.get("validates-and-escapes-untrusted-input"))
                .containsExactly(new SourceKind("scm.pull-request.diff"));
    }
}
