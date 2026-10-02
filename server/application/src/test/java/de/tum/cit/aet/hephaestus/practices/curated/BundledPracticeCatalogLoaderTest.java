package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndexBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PracticePreconditionEvaluator;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionValidator;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptionsFixture;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class BundledPracticeCatalogLoaderTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final ClasspathArtifactSourceCatalogRegistry catalogs =
            new ClasspathArtifactSourceCatalogRegistry(objectMapper, java.time.Clock.systemUTC());
    private final BundledPracticeCatalogLoader loader = new BundledPracticeCatalogLoader(
            objectMapper,
            new PracticeDefinitionValidator(catalogs, PracticeSignalOptionsFixture.real()),
            new PracticeEvidenceDefaults(catalogs, PracticeSignalOptionsFixture.catalog()));

    @ParameterizedTest
    @ValueSource(
            strings = {
                "engaging-with-inline-review-comments",
                "merged-past-unresolved-review-threads",
                "defers-review-asks-into-tracked-work"
            })
    void shouldRefuseShippedReviewPracticesWhenEitherCommentCorpusIsPartial(String slug) {
        var definition = loader.catalog().practices().stream()
                .filter(entry -> entry.slug().equals(slug))
                .findFirst()
                .orElseThrow()
                .definition();
        Practice practice = new Practice();
        practice.setSlug(slug);
        practice.setSignals(definition.signals());
        practice.setEvidenceRequirements(definition.evidenceRequirements());
        practice.setReviewWhen(definition.reviewWhen());
        practice.setSubject(definition.subject());
        practice.setAutomatedReviewPolicy(definition.automatedReviewPolicy());
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var builder = new JobFolderIndexBuilder(
                objectMapper,
                catalogs,
                new PracticePreconditionEvaluator(objectMapper),
                new AutomatedReviewFence(Map.of()),
                Clock.fixed(now, java.time.ZoneOffset.UTC));
        for (String partialKind : List.of("", "scm.pull-request.comments", "scm.general-review-comments")) {
            var captures = catalogs.current().sources().stream()
                    .map(source -> new SourceCapture(
                            source.kind(),
                            new SourceCaptureState.Available(
                                    SourceContentState.NON_EMPTY,
                                    source.kind().value().equals(partialKind)
                                                    || !source.completenessPolicy()
                                                            .supportsComplete()
                                            ? SourceCompleteness.PARTIAL
                                            : SourceCompleteness.COMPLETE,
                                    new SourceCaptureFacts(now, null, null, null)),
                            List.of(new SourceArtifact(
                                    "context/" + source.kind().value() + ".json",
                                    "application/json",
                                    "a".repeat(64),
                                    2))))
                    .toList();
            var manifest = new JobFolderIndex(
                    catalogs.current().version(),
                    catalogs.catalogDigest(),
                    definition.artifactKind().value(),
                    now,
                    captures);
            var result = builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice));
            if (partialKind.isEmpty()) {
                assertThat(result.readyPractices()).containsExactly(practice);
            } else {
                assertThat(result.readyPractices()).isEmpty();
                assertThat(result.decisions().getFirst().sourceChecks())
                        .filteredOn(check -> check.sourceKind().equals(new SourceKind(partialKind)))
                        .singleElement()
                        .satisfies(check -> assertThat(check.reasonCodes())
                                .containsExactly(SourceReadinessReason.SOURCE_INCOMPLETE));
            }
        }
    }

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

    @Test
    void shouldMakeUnsupportedEvidenceBoundariesExplicitForHumanReview() {
        var humanReview = loader.catalog().practices().stream()
                .filter(practice -> !practice.definition()
                        .automatedReviewPolicy()
                        .automatedReview()
                        .canAttemptAutomatedReview())
                .toList();
        assertThat(humanReview)
                .extracting(practice -> practice.slug(), practice -> {
                    var reason = practice.definition().automatedReviewPolicy().insufficiencyReason();
                    assertThat(reason).isNotNull();
                    return reason.code();
                })
                .containsExactlyInAnyOrder(
                        tuple("issue-closed-with-unmet-outcome", "AT_CLOSE_STATE_NOT_CAPTURED"),
                        tuple(
                                "triages-the-issue-with-labels-and-ownership",
                                "PROJECT_CLASSIFICATION_SCHEME_NOT_CAPTURED"));
        assertThat(humanReview).allSatisfy(practice -> {
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
        // A handoff practice reads the description as written; only code practices are framed by the diff.
        assertThat(preambleOf(catalog, "honours-linked-issue-acceptance-criteria"))
                .isEqualTo(preambleOf(catalog, "describe-what-and-why"))
                .isNotEqualTo(preambleOf(catalog, "ships-tests-with-the-change"));
    }

    private static String preambleOf(BundledPracticeCatalog catalog, String slug) {
        String criteria = catalog.practices().stream()
                .filter(practice -> practice.slug().equals(slug))
                .findFirst()
                .orElseThrow()
                .definition()
                .criteria();
        return criteria.substring(0, criteria.indexOf("\n\n---\n\n"));
    }

    /** Gates prevent model reviews when complete evidence proves that the subject is absent. */
    @Test
    void shouldShipTheSubjectDeclarationsThatKeepPracticesFromBeingAskedForNothing() {
        BundledPracticeCatalog catalog = loader.catalog();

        assertThat(catalog.practices().stream()
                        .filter(practice -> practice.definition().precondition() != null)
                        .map(practice -> practice.slug()))
                .containsExactlyInAnyOrder(
                        "engaging-with-inline-review-comments",
                        "keeps-views-free-of-networking-and-persistence",
                        "owns-state-at-the-right-level",
                        "makes-ui-accessible-by-default",
                        "uses-structured-concurrency-safely",
                        "ships-a-preview-with-each-new-view",
                        "declares-permissions-truthfully-at-point-of-use",
                        "uses-adaptive-colors-for-every-appearance");
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
                .satisfies(practice ->
                        assertThat(practice.definition().reviewWhen()).isEmpty());
        assertThat(loader.catalog().practices())
                .filteredOn(practice -> practice.slug().equals("merges-only-after-approval"))
                .singleElement()
                .satisfies(practice -> {
                    var occasion = practice.definition();
                    assertThat(occasion.subject()).isEqualTo(ActorRole.MERGER);
                    assertThat(occasion.reviewWhen())
                            .containsExactlyEntriesOf(Map.of("draftStatus", Set.of("NOT_DRAFT")));
                });
    }

    @Test
    void shouldPreserveTheOccasionWhenEvidenceRequirementsAreOmitted() {
        var occasion = objectMapper.readValue("""
                {
                  "signals": ["scm.pull_request.merged"],
                  "subject": "MERGER",
                  "reviewWhen": {},
                  "precondition": {
                    "skipReason": "the change has no Swift code",
                    "anyOf": [{"changedPathMatches": ["**/*.swift"]}]
                  }
                }
                """, BundledPracticeCatalogLoader.CatalogOccasion.class);

        assertThat(occasion.evidenceRequirements()).isNull();
        assertThat(occasion.subject()).isEqualTo(ActorRole.MERGER);
        assertThat(occasion.reviewWhen()).isEmpty();
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
