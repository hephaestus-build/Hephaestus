package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class BundledPracticeCatalogLoaderTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final ClasspathArtifactSourceCatalogRegistry catalogs =
            new ClasspathArtifactSourceCatalogRegistry(objectMapper, java.time.Clock.systemUTC());
    private final BundledPracticeCatalogLoader loader = new BundledPracticeCatalogLoader(
            objectMapper,
            new PracticeDefinitionValidator(catalogs, PracticeSignalOptionsFixture.real()),
            new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()));

    @Test
    void shouldKeepCaptureFailuresOutOfObservationStatuses() {
        assertThat(loader.catalog().practices())
                .allSatisfy(practice -> assertThat(practice.definition().criteria())
                        .as("canonical observation vocabulary in %s", practice.slug())
                        .doesNotContain("INCONCLUSIVE", "NO_REVIEW_OCCASION", "INSUFFICIENT_EVIDENCE"));
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> java.util.Set.of(
                                "describe-what-and-why", "merged-past-unresolved-review-threads",
                                "records-significant-decisions-with-rationale", "asks-answerable-questions")
                        .contains(practice.slug()))
                .hasSize(4)
                .allSatisfy(practice -> assertThat(practice.definition().criteria())
                        .contains("collection gap")
                        .doesNotContain("you could not read it: return UNDETERMINED"));
    }

    @Test
    void shouldNotCallApplicableGoodWorkNotApplicable() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("asks-answerable-questions")
                        || practice.slug().equals("posts-clear-status-and-blocker-updates"))
                .hasSize(2)
                .allSatisfy(practice ->
                        assertThat(practice.definition().criteria()).contains("PRESENT/GOOD, not NOT_APPLICABLE"));
    }

    @Test
    void shouldLoadComposedDefinitionsAndScripts() {
        BundledPracticeCatalog catalog = loader.catalog();

        assertThat(catalog.groups())
                .allSatisfy(group -> assertThat(group.definition().name()).isNotBlank());
        assertThat(catalog.practices())
                .allSatisfy(
                        practice -> assertThat(practice.definition().criteria()).contains("\n\n---\n\n"));
        assertThat(catalog.practices())
                .filteredOn(practice -> practice.slug().equals("ships-tests-with-the-change"))
                .singleElement()
                .satisfies(practice ->
                        assertThat(practice.definition().precomputeScript()).isNotBlank());
    }

    /**
     * The loader runs every shipped practice through {@link PracticeDefinitionValidator}, so a second
     * {@code on} entry would fail the boot rather than reach a workspace. Asserted on the composed
     * definitions as well, because a bare-string entry expands into a binding without looking like one.
     */
    /**
     * The declarations that stop us spending a model call on a question the staged evidence already
     * answers. Pinned by slug because the value of each is measured — on the corpus these were written
     * against, they account for the great majority of every {@code NOT_APPLICABLE} ever recorded — and a
     * declaration dropped in an edit would restore that cost in silence.
     */
    @Test
    void shouldShipTheSubjectDeclarationsThatKeepPracticesFromBeingAskedForNothing() {
        BundledPracticeCatalog catalog = loader.catalog();

        assertThat(catalog.practices().stream()
                        .filter(practice ->
                                practice.definition().bindings().getFirst().appliesWhen() != null)
                        .map(practice -> practice.slug()))
                .containsExactlyInAnyOrder(
                        "changes-dependencies-deliberately",
                        "keeps-the-test-suite-honest",
                        "engaging-with-inline-review-comments");
    }

    /**
     * Every declaration says, in the author's voice, what its absence means. A skip with no sentence is
     * the silence this whole mechanism exists to stop producing.
     */
    @Test
    void shouldGiveEverySubjectDeclarationASentenceForTheReader() {
        assertThat(loader.catalog().practices()).allSatisfy(practice -> {
            var subject = practice.definition().bindings().getFirst().appliesWhen();
            if (subject == null) {
                return;
            }
            assertThat(subject.absentSays())
                    .as("%s must explain its own silence", practice.slug())
                    .isNotBlank()
                    .doesNotContain("NOT_APPLICABLE");
            assertThat(subject.anyOf()).isNotEmpty();
        });
    }

    @Test
    void shouldShipOneOccasionPerPractice() {
        assertThat(loader.catalog().practices())
                .allSatisfy(practice -> assertThat(practice.definition().bindings())
                        .as("occasions of '%s'", practice.slug())
                        .hasSize(1));
    }

    @Test
    void shouldKeepDetectorVocabularyOutOfDeveloperCopy() {
        Pattern detectorVocabulary = Pattern.compile("\\b(?:PRESENT|ABSENT|GOOD|BAD|NOT_APPLICABLE)\\b");

        assertThat(loader.catalog().practices()).allSatisfy(practice -> {
            assertThat(practice.definition().whyItMatters())
                    .as("whyItMatters for '%s'", practice.slug())
                    .isNotNull()
                    .doesNotContainPattern(detectorVocabulary);
            assertThat(practice.definition().whatGoodLooksLike())
                    .as("whatGoodLooksLike for '%s'", practice.slug())
                    .isNotNull()
                    .doesNotContainPattern(detectorVocabulary);
        });
    }

    @Test
    void shouldUseRealNewlinesInCriteria() {
        assertThat(loader.catalog().practices())
                .allSatisfy(practice -> assertThat(practice.definition().criteria())
                        .as("criteria for '%s'", practice.slug())
                        .doesNotContain("\\n"));
    }

    @Test
    void shouldKeepFeedbackInstructionsOutOfMeasurementCriteria() {
        assertThat(loader.catalog().practices())
                .allSatisfy(
                        practice -> assertThat(practice.definition().criteria().toLowerCase(Locale.ROOT))
                                .as("measurement criteria for '%s'", practice.slug())
                                .doesNotContain("guidance"));
    }
}
