package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
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
    void shouldDefinePracticeStandardsWithoutLegacyAxes() {
        assertThat(loader.catalog().practices()).allSatisfy(practice -> {
            assertThat(practice.definition().criteria())
                    .contains("REVIEW FOCUS:", "MET", "NOT_MET")
                    .doesNotContain(
                            "TARGET ASSESSMENT:",
                            "fixed target",
                            "DEFECT-DETECTOR DISCIPLINE",
                            "PRESENT/GOOD",
                            "PRESENT/BAD",
                            "ABSENT/GOOD",
                            "ABSENT/BAD");
        });
    }

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
    void shouldDistinguishConformanceFromInapplicability() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("asks-answerable-questions")
                        || practice.slug().equals("posts-clear-status-and-blocker-updates"))
                .hasSize(2)
                .allSatisfy(
                        practice -> assertThat(practice.definition().criteria()).contains("MET", "not NOT_APPLICABLE"));
    }

    /** Pinned by slug so that shipping another practice without automated review is a decision. */
    @Test
    void shouldShipOnlyTheCloseOutcomePracticeAsNeedingHumanReview() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> !practice.definition()
                        .automatedReviewPolicy()
                        .automatedReview()
                        .canAttemptAutomatedReview())
                .singleElement()
                .satisfies(practice -> {
                    assertThat(practice.slug()).isEqualTo("issue-closed-with-unmet-outcome");
                    assertThat(practice.definition().automatedReviewPolicy().insufficiencyReason())
                            .isNotNull()
                            .extracting(PracticeEvidenceLimitation::code)
                            .isEqualTo("AT_CLOSE_STATE_NOT_CAPTURED");
                    assertThat(practice.definition().precomputeScript()).isNull();
                    assertThat(loader.holdsAs(practice.slug())).isEmpty();
                });
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

    /** Gates prevent model reviews when complete evidence proves that the subject is absent. */
    @Test
    void shouldShipTheSubjectDeclarationsThatKeepPracticesFromBeingAskedForNothing() {
        BundledPracticeCatalog catalog = loader.catalog();

        assertThat(catalog.practices().stream()
                        .filter(practice -> practice.definition().precondition() != null)
                        .map(practice -> practice.slug()))
                .containsExactlyInAnyOrder(
                        "changes-dependencies-deliberately",
                        "keeps-the-test-suite-honest",
                        "engaging-with-inline-review-comments",
                        "keeps-views-free-of-networking-and-persistence",
                        "owns-state-at-the-right-level",
                        "makes-ui-accessible-by-default",
                        "uses-structured-concurrency-safely",
                        "ships-a-preview-with-each-new-view",
                        "declares-permissions-truthfully-at-point-of-use",
                        "uses-adaptive-colors-for-every-appearance",
                        "avoids-insecure-defaults-and-over-broad-permissions",
                        "validates-and-escapes-untrusted-input");
    }

    @Test
    void shouldJudgeReviewersForReviewerPractices() {
        assertThat(loader.catalog().practices().stream()
                        .filter(practice -> practice.definition().subject() == ActorRole.REVIEWER)
                        .map(practice -> practice.slug()))
                .containsExactlyInAnyOrder(
                        "leaves-useful-specific-review-comments",
                        "reviews-respectfully-asks-rather-than-demands",
                        "reviews-substantively-with-understanding");
    }

    /**
     * Every declaration says, in the author's voice, what its absence means. A skip with no sentence is
     * the silence this whole mechanism exists to stop producing.
     */
    @Test
    void shouldGiveEverySubjectDeclarationASentenceForTheReader() {
        assertThat(loader.catalog().practices()).allSatisfy(practice -> {
            var subject = practice.definition().precondition();
            if (subject == null) {
                return;
            }
            assertThat(subject.skipReason())
                    .as("%s must explain its own silence", practice.slug())
                    .isNotBlank()
                    .doesNotContain("NOT_APPLICABLE");
            assertThat(subject.anyOf()).isNotEmpty();
        });
    }

    @Test
    void shouldPreserveDraftEligibilityAndTheMergeSubjectFromFlatFields() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("ready-and-traceable-handoff"))
                .singleElement()
                .satisfies(
                        practice -> assertThat(practice.definition().onDrafts()).isTrue());
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("merges-only-after-approval"))
                .singleElement()
                .satisfies(practice -> {
                    var occasion = practice.definition();
                    assertThat(occasion.subject()).isEqualTo(ActorRole.MERGER);
                    assertThat(occasion.onDrafts()).isFalse();
                });
    }

    @Test
    void shouldPreserveTheOccasionWhenEvidenceRequirementsAreOmitted() {
        var occasion = objectMapper.readValue("""
                {
                  "signals": ["scm.pull_request.merged"],
                  "subject": "MERGER",
                  "onDrafts": true,
                  "precondition": {
                    "skipReason": "the change has no Swift code",
                    "anyOf": [{"changedPathMatches": ["**/*.swift"]}]
                  }
                }
                """, BundledPracticeCatalogLoader.CatalogOccasion.class);

        assertThat(occasion.evidenceRequirements()).isNull();
        assertThat(occasion.subject()).isEqualTo(ActorRole.MERGER);
        assertThat(occasion.onDrafts()).isTrue();
        assertThat(occasion.precondition()).isNotNull().satisfies(precondition -> {
            assertThat(precondition.skipReason()).isEqualTo("the change has no Swift code");
            assertThat(precondition.anyOf())
                    .singleElement()
                    .satisfies(clause -> assertThat(clause.changedPathMatches()).containsExactly("**/*.swift"));
        });
    }

    @Test
    void shouldKeepCompleteDiffCoverageAndContextualCheckoutRequirements() {
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("keeps-views-free-of-networking-and-persistence"))
                .singleElement()
                .satisfies(practice -> {
                    assertThat(practice.definition().evidenceRequirements())
                            .extracting(
                                    requirement -> requirement.sourceKind().value(),
                                    PracticeEvidenceRequirement::stance)
                            .containsExactlyInAnyOrder(
                                    tuple("scm.pull-request.diff", EvidenceStance.EXHAUSTIVE),
                                    tuple("scm.repository.tree", EvidenceStance.CONTEXTUAL));
                });
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

    /**
     * The practice profile prints this sentence beside a practice that holds, so it has to read as one line
     * about what the developer keeps doing, in the same developer voice as the guidance and never in the
     * review's own vocabulary. The catalog schema owns its shape — length and no dashes — and rejects a phrase
     * that breaks it at authoring time.
     */
    @Test
    void shouldGiveEveryPracticeOneSentenceForWhenItHolds() {
        Pattern reviewVocabulary = Pattern.compile("\\b(?:PRESENT|ABSENT|GOOD|BAD|NOT_APPLICABLE)\\b");

        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.definition()
                        .automatedReviewPolicy()
                        .automatedReview()
                        .canAttemptAutomatedReview())
                .allSatisfy(practice -> assertThat(loader.holdsAs(practice.slug()))
                        .as("holdsAs for '%s'", practice.slug())
                        .hasValueSatisfying(phrase -> assertThat(phrase).doesNotContainPattern(reviewVocabulary)));
        assertThat(loader.holdsAs("not-a-bundled-practice")).isEmpty();
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
