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
import java.util.Set;
import java.util.regex.Pattern;
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

    @Test
    void shouldRequireCompleteCaptureOnlyFromSourcesThatCanProvideIt() {
        loader.catalog()
                .practices()
                .forEach(practice -> practice.definition().evidenceRequirements().stream()
                        .filter(need -> need.stance() == EvidenceStance.EXHAUSTIVE)
                        .forEach(need -> assertThat(registry.requireSource(
                                                registry.current().version(), need.sourceKind())
                                        .completenessPolicy()
                                        .supportsComplete())
                                .as("%s requires complete capture of %s", practice.slug(), need.sourceKind())
                                .isTrue()));
    }

    /**
     * A practice that declares only the change and the repository tree may have its earlier answer reused on
     * identical code, so its criteria must read nothing else. {@code context/change.json} is the change's own pinned
     * range; every other staged context file belongs to a source such a practice would have to declare.
     */
    @Test
    void shouldDeclareEveryContextFileACodeOnlyPracticeReads() {
        Set<SourceKind> code = Set.of(new SourceKind("scm.pull-request.diff"), new SourceKind("scm.repository.tree"));
        Pattern contextFile = Pattern.compile("context/([a-z_]+\\.[a-z]+)");
        assertThat(loader.catalog().practices())
                .filteredOn(practice ->
                        !practice.definition().evidenceRequirements().isEmpty()
                                && practice.definition().evidenceRequirements().stream()
                                        .allMatch(need -> code.contains(need.sourceKind())))
                .allSatisfy(practice -> assertThat(contextFile
                                .matcher(practice.definition().criteria())
                                .results()
                                .map(match -> match.group(1)))
                        .as("%s declares only the change and the tree", practice.slug())
                        .allMatch("change.json"::equals));
    }

    @Test
    void shouldKeepChangedCodeAbsenceClaimsWithinTheDiff() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> Set.of(
                                "handles-errors-instead-of-swallowing-them",
                                "validates-and-escapes-untrusted-input",
                                "keeps-views-free-of-networking-and-persistence")
                        .contains(practice.slug()))
                .hasSize(3)
                .allSatisfy(practice -> assertThat(practice.definition().evidenceRequirements().stream()
                                .filter(need -> need.stance() == EvidenceStance.EXHAUSTIVE)
                                .map(need -> need.sourceKind()))
                        .containsExactly(new SourceKind("scm.pull-request.diff")));
    }
}
