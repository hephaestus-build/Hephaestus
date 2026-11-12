package de.tum.cit.aet.hephaestus.practices.research;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery.ResearchQuestion;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.BasisRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.DecisionRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.FeedbackRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ItemRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.PracticeRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.PracticeVersionRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ProblemRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ResponseRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ResultRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewPracticeRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewRow;
import de.tum.cit.aet.hephaestus.practices.research.ResearchSnapshot.ReviewSubjectRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageAttempt;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewUsageSnapshot;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ResearchDatasetAssemblerTest extends BaseUnitTest {

    private static final long PARTICIPANT = 9_001L;
    private static final long SECOND = 9_003L;
    private static final long OUTSIDER = 9_002L;
    private static final Instant FROM = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-06-15T12:30:00Z");
    private static final String BUNDLED_DIGEST = "v6:" + "a".repeat(64);
    private static final String CUSTOM_DIGEST = "v6:" + "b".repeat(64);
    private static final String PUBLIC_CRITERIA = "Keep each pull request focused on one change.";
    private static final String PRIVATE_CRITERIA = "SENTINEL_PRIVATE_CRITERION mentions Alice Example";
    private static final ResearchDataset.StandardSource SOURCE = new ResearchDataset.StandardSource(
            ResearchDisclosureService.CATALOG_RESOURCE, "1.2.3", "RELEASE", "c".repeat(40));

    private final UUID review = UUID.randomUUID();
    private final UUID mixedReview = UUID.randomUUID();
    private final UUID legacyReview = UUID.randomUUID();
    private final UUID legacy = UUID.randomUUID();
    private final UUID bound = UUID.randomUUID();
    private final UUID incomplete = UUID.randomUUID();
    private final UUID mixed = UUID.randomUUID();
    private final UUID outsideWindow = UUID.randomUUID();
    private final UUID outsiderResult = UUID.randomUUID();
    private final UUID legacyProblem = UUID.randomUUID();
    private final UUID complete = UUID.randomUUID();
    private final UUID partial = UUID.randomUUID();
    private final UUID unsupported = UUID.randomUUID();
    private final UUID outsiderFeedback = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();

    private ResearchSnapshot snapshot(Map<UUID, ReviewUsageSnapshot> usage) {
        return new ResearchSnapshot(
                41L,
                Instant.parse("2026-10-20T06:00:00Z"),
                new ResearchQuestion("2026-10-04", "AET"),
                Map.of(PARTICIPANT, 77_001L, SECOND, 77_003L),
                List.of(
                        // Migrated: retains its practice version and an end marker, but no capture contract.
                        new ResultRow(
                                legacy,
                                legacyReview,
                                11L,
                                31L,
                                21L,
                                PARTICIPANT,
                                "NOT_MET",
                                "MAJOR",
                                (short) 0,
                                false,
                                false,
                                AT,
                                AT.plusSeconds(60),
                                "WORK_CHANGED",
                                null),
                        new ResultRow(
                                bound,
                                review,
                                11L,
                                31L,
                                21L,
                                PARTICIPANT,
                                "MET",
                                null,
                                (short) 1,
                                true,
                                true,
                                AT,
                                null,
                                null,
                                "INCORRECT"),
                        // A current-contract number without the identities it should carry.
                        new ResultRow(
                                incomplete,
                                review,
                                12L,
                                null,
                                21L,
                                SECOND,
                                "NOT_MET",
                                "MINOR",
                                (short) 1,
                                false,
                                false,
                                AT,
                                null,
                                null,
                                null),
                        new ResultRow(
                                mixed,
                                mixedReview,
                                12L,
                                32L,
                                22L,
                                PARTICIPANT,
                                "MET",
                                null,
                                (short) 1,
                                true,
                                true,
                                AT,
                                null,
                                null,
                                null),
                        new ResultRow(
                                outsiderResult,
                                mixedReview,
                                12L,
                                32L,
                                22L,
                                OUTSIDER,
                                "NOT_MET",
                                "MINOR",
                                (short) 1,
                                true,
                                false,
                                AT,
                                null,
                                null,
                                null)),
                List.of(new ProblemRow(legacyProblem, legacy, 0, "MAJOR")),
                List.of(
                        new ReviewRow(review, "ITEM", "EVENT", "COMPLETE", AT, AT),
                        new ReviewRow(mixedReview, "SCAN", "SCHEDULE", "COMPLETE", AT, AT),
                        new ReviewRow(legacyReview, "ITEM", "BACKFILL", "COMPLETE", AT, null)),
                List.of(
                        // Two consenting subjects, every assignment judged: whole-review facts are shared, not
                        // allocated.
                        new ReviewSubjectRow(review, 2, 0, 0, Set.of(PARTICIPANT, SECOND)),
                        new ReviewSubjectRow(mixedReview, 3, 1, 0, Set.of(PARTICIPANT, OUTSIDER)),
                        new ReviewSubjectRow(legacyReview, 0, 0, 0, Set.of(PARTICIPANT))),
                List.of(
                        new ReviewPracticeRow(review, 11L, 31L, "DONE", null, 1, true, 2, 0, 2, 0, 0),
                        new ReviewPracticeRow(
                                mixedReview, 12L, 32L, "WITHHELD", "free text, not a code", 3, true, 3, 1, 2, 0, 0),
                        new ReviewPracticeRow(
                                legacyReview, 11L, 31L, "WITHHELD", "NO_RECORDED_RESULT", null, null, 0, 0, 0, 0, 0)),
                usage,
                List.of(new ItemRow(21L, "scm.pull_request"), new ItemRow(22L, "scm.pull_request")),
                List.of(new PracticeRow(11L, "small-pull-requests"), new PracticeRow(12L, "private-custom")),
                List.of(
                        new PracticeVersionRow(31L, 11L, 3, BUNDLED_DIGEST),
                        new PracticeVersionRow(32L, 12L, 1, CUSTOM_DIGEST)),
                List.of(
                        new FeedbackRow(complete, review, 21L, PARTICIPANT, "ON_WORK", "DELIVERED", null, AT, AT, null),
                        new FeedbackRow(
                                partial, review, null, PARTICIPANT, "PRACTICE_PAGE", "READY", null, AT, null, complete),
                        new FeedbackRow(
                                unsupported,
                                review,
                                null,
                                PARTICIPANT,
                                "PRACTICE_PAGE",
                                "WITHHELD",
                                "NOTHING_NEW",
                                AT,
                                null,
                                outsideWindow),
                        new FeedbackRow(
                                outsiderFeedback,
                                mixedReview,
                                22L,
                                OUTSIDER,
                                "ON_WORK",
                                "READY",
                                null,
                                AT,
                                null,
                                null)),
                List.of(
                        new BasisRow(complete, legacy, legacyProblem, 0),
                        new BasisRow(complete, bound, null, 1),
                        new BasisRow(partial, bound, null, 0),
                        // The second half of this piece's support lies outside the window.
                        new BasisRow(partial, outsideWindow, null, 1)),
                List.of(new DecisionRow(complete, "WITHDRAW", AT), new DecisionRow(complete, "RESTORE", AT)),
                List.of(new ResponseRow(complete, "DISAGREE", null, AT)));
    }

    private ResearchDatasetAssembler.Assembled assemble(ResearchPseudonyms keys, Map<UUID, ReviewUsageSnapshot> usage) {
        return ResearchDatasetAssembler.assemble(
                snapshot(usage),
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                FROM,
                TO,
                Instant.parse("2036-10-01T00:00:00Z"),
                Map.of(
                        "small-pull-requests",
                        new ResearchDatasetAssembler.BundledStandard(BUNDLED_DIGEST, PUBLIC_CRITERIA)),
                SOURCE,
                keys);
    }

    private ResearchDatasetAssembler.Assembled assemble(ResearchPseudonyms keys) {
        return assemble(keys, Map.of());
    }

    private static ResearchPseudonyms key(int seed) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) seed);
        return ResearchPseudonyms.withKey(bytes);
    }

    private static ResearchDataset.Review reviewWithScope(ResearchDataset dataset, ReviewSubjectScope scope) {
        return dataset.reviews().stream()
                .filter(r -> r.subjectScope().equals(scope.name()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void shouldKeepLegacyResultsAndDeriveProofFromRecordedIdentitiesNotTheVersionNumber() {
        var dataset = assemble(key(1)).dataset();

        assertThat(dataset.results()).hasSize(4);
        var legacyRecord = dataset.results().stream()
                .filter(r -> r.evidenceVersion() == 0)
                .findFirst()
                .orElseThrow();
        assertThat(legacyRecord.proof()).isEqualTo("LEGACY_CAPTURE_CONTRACT_NOT_ESTABLISHED");
        assertThat(legacyRecord.practiceVersion())
                .as("a retained version is kept")
                .isNotNull();
        assertThat(legacyRecord.currentness()).isEqualTo("ENDED");
        assertThat(legacyRecord.endReason()).isEqualTo("WORK_CHANGED");
        assertThat(legacyRecord.resultCoverageRecord()).isEqualTo("NOT_RECORDED");
        assertThat(dataset.problems())
                .singleElement()
                .satisfies(p -> assertThat(p.result()).isEqualTo(legacyRecord.key()));
        assertThat(dataset.results())
                .filteredOn(r -> r.evidenceVersion() == 1)
                .extracting(ResearchDataset.Result::proof)
                .containsExactlyInAnyOrder(
                        "VERSION_IDENTITIES_RECORDED", "VERSION_IDENTITIES_INCOMPLETE", "VERSION_IDENTITIES_RECORDED");
        assertThat(dataset.limitations())
                .contains("LEGACY_RESULTS_CAPTURE_CONTRACT_NOT_ESTABLISHED", "RESULT_COVERAGE_IS_PER_RESULT_MARKER")
                .doesNotContain("MODEL_USAGE_NOT_EXPORTED");
    }

    @Test
    void shouldExportFeedbackOnlyWithItsWholeSupport() {
        var dataset = assemble(key(1)).dataset();

        assertThat(dataset.feedback())
                .as("partial support and the outsider's piece are absent, not trimmed")
                .hasSize(2);
        assertThat(dataset.counts().feedbackExcludedIncompleteBasis()).isEqualTo(1);
        var full = dataset.feedback().stream()
                .filter(f -> f.basis().size() == 2)
                .findFirst()
                .orElseThrow();
        assertThat(full.basis().get(0).problem()).isNotNull();
        assertThat(full.decisions())
                .extracting(ResearchDataset.Decision::action)
                .contains("WITHDRAW", "RESTORE");
        assertThat(full.responses())
                .singleElement()
                .satisfies(r -> assertThat(r.usefulness()).isNull());
        var unsupportedRecord = dataset.feedback().stream()
                .filter(f -> f.basis().isEmpty())
                .findFirst()
                .orElseThrow();
        assertThat(unsupportedRecord.replaces()).isNull();
        assertThat(unsupportedRecord.replacesNotExported()).isTrue();
    }

    @Test
    void shouldReportWholeReviewUsageAsTheOwnerGaveItWithZeroAndNullPricingKeptApart() {
        var usage = new ReviewUsageSnapshot(
                new BigDecimal("0.0000"),
                true,
                5,
                1_000,
                400,
                List.of(
                        new ReviewUsageAttempt(
                                job,
                                0,
                                3,
                                600,
                                250,
                                90,
                                100,
                                0,
                                new BigDecimal("0.0000"),
                                UsagePricingState.NO_CHARGE,
                                false),
                        new ReviewUsageAttempt(
                                job,
                                1,
                                2,
                                400,
                                150,
                                40,
                                0,
                                10,
                                new BigDecimal("0.0000"),
                                UsagePricingState.NO_CHARGE,
                                false)));
        var dataset =
                assemble(key(1), Map.of(review, usage, mixedReview, usage)).dataset();

        var shared = reviewWithScope(dataset, ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING)
                .usage();
        assertThat(shared.state()).isEqualTo("RETAINED");
        assertThat(shared.allocation()).isEqualTo("WHOLE_REVIEW_NOT_ALLOCATED");
        assertThat(shared.measurementCoverage()).isEqualTo("NOT_RECORDED");
        assertThat(shared.priced()).isTrue();
        assertThat(shared.costUsd()).as("measured no-charge, not missing").isEqualTo("0.0000");
        assertThat(shared.outputTokens())
                .as("the owner's total; reasoning is not added again")
                .isEqualTo(400L);
        assertThat(shared.attempts())
                .extracting(ResearchDataset.UsageAttempt::attempt)
                .containsExactly(0, 1);
        assertThat(shared.attempts())
                .extracting(ResearchDataset.UsageAttempt::job)
                .doesNotContain(job.toString());

        var mixedUsage = reviewWithScope(dataset, ReviewSubjectScope.INCLUDES_NON_PARTICIPANT)
                .usage();
        assertThat(mixedUsage.state()).isEqualTo("UNAVAILABLE");
        assertThat(mixedUsage.unavailableReason()).isEqualTo("INCLUDES_NON_PARTICIPANT");
        assertThat(mixedUsage.costUsd()).isNull();
        assertThat(mixedUsage.calls()).isNull();
        assertThat(mixedUsage.attempts()).isEmpty();

        var legacyUsage = reviewWithScope(dataset, ReviewSubjectScope.ASSIGNMENTS_NOT_RECORDED)
                .usage();
        assertThat(legacyUsage.unavailableReason()).isEqualTo("ASSIGNMENTS_NOT_RECORDED");
    }

    @Test
    void shouldKeepUnpricedUsageNullAndAbsentLedgerRowsDistinctFromZero() {
        var unpriced = new ReviewUsageSnapshot(
                null,
                false,
                2,
                300,
                120,
                List.of(
                        new ReviewUsageAttempt(
                                job, 0, 1, 100, 60, 0, 0, 0, new BigDecimal("0.0100"), UsagePricingState.PRICED, false),
                        new ReviewUsageAttempt(job, 1, 1, 200, 60, 0, 0, 0, null, UsagePricingState.UNPRICED, false)));

        var priced = reviewWithScope(
                        assemble(key(1), Map.of(review, unpriced)).dataset(),
                        ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING)
                .usage();
        assertThat(priced.priced()).isFalse();
        assertThat(priced.costUsd()).isNull();
        assertThat(priced.attempts())
                .extracting(ResearchDataset.UsageAttempt::costUsd)
                .containsExactly("0.0100", null);
        assertThat(priced.attempts())
                .extracting(ResearchDataset.UsageAttempt::pricingState)
                .containsExactly("PRICED", "UNPRICED");

        var absent = reviewWithScope(assemble(key(1)).dataset(), ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING)
                .usage();
        assertThat(absent.state()).isEqualTo("NO_RETAINED_LEDGER_ROWS");
        assertThat(absent.calls()).isNull();
        assertThat(absent.priced()).isNull();
    }

    @Test
    void shouldClassifyReviewSubjectsFromAssignmentAndResultFacts() {
        Set<Long> participants = Set.of(PARTICIPANT, SECOND);

        assertThat(ReviewSubjectScope.of(new ReviewSubjectRow(review, 2, 0, 0, Set.of(PARTICIPANT)), participants))
                .isEqualTo(ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING);
        assertThat(ReviewSubjectScope.of(new ReviewSubjectRow(review, 2, 1, 0, Set.of(PARTICIPANT)), participants))
                .as("a listed but unjudged assignment may be about someone else")
                .isEqualTo(ReviewSubjectScope.ATTRIBUTION_UNKNOWN);
        assertThat(ReviewSubjectScope.of(new ReviewSubjectRow(review, 2, 0, 1, Set.of(PARTICIPANT)), participants))
                .isEqualTo(ReviewSubjectScope.ATTRIBUTION_UNKNOWN);
        assertThat(ReviewSubjectScope.of(
                        new ReviewSubjectRow(review, 2, 1, 0, Set.of(PARTICIPANT, OUTSIDER)), participants))
                .isEqualTo(ReviewSubjectScope.INCLUDES_NON_PARTICIPANT);
        assertThat(ReviewSubjectScope.of(new ReviewSubjectRow(review, 0, 0, 0, Set.of(PARTICIPANT)), participants))
                .isEqualTo(ReviewSubjectScope.ASSIGNMENTS_NOT_RECORDED);
        assertThat(ReviewSubjectScope.of(null, participants)).isEqualTo(ReviewSubjectScope.ASSIGNMENTS_NOT_RECORDED);
    }

    @Test
    void shouldWithholdWholeReviewCountsUnlessEverySubjectParticipatesAndOnlyExportRecordedCodes() {
        var dataset = assemble(key(1)).dataset();

        var shared = reviewWithScope(dataset, ReviewSubjectScope.ALL_SUBJECTS_PARTICIPATING);
        assertThat(shared.practices()).singleElement().satisfies(run -> {
            assertThat(run.status()).isEqualTo("DONE");
            assertThat(run.itemsListed()).isEqualTo(1);
            assertThat(run.assignments()).isEqualTo(new ResearchDataset.Assignments(true, 0, 2, 0, 0));
        });
        var mixedRun = reviewWithScope(dataset, ReviewSubjectScope.INCLUDES_NON_PARTICIPANT)
                .practices()
                .getFirst();
        assertThat(mixedRun.reason()).isEqualTo("UNLISTED_CODE");
        assertThat(mixedRun.itemsListed()).isNull();
        assertThat(mixedRun.assignments()).isNull();
        var legacyRun = reviewWithScope(dataset, ReviewSubjectScope.ASSIGNMENTS_NOT_RECORDED)
                .practices()
                .getFirst();
        assertThat(legacyRun.reason()).isEqualTo("NO_RECORDED_RESULT");
        assertThat(legacyRun.assignments()).isNull();
    }

    @Test
    void shouldIncludePublicBundledCriteriaOnlyForExactBundleMatchesAndNeverPrivateOnes() throws Exception {
        var assembled = assemble(key(1));
        var dataset = assembled.dataset();

        assertThat(dataset.standards()).singleElement().satisfies(s -> {
            assertThat(s.slug()).isEqualTo("small-pull-requests");
            assertThat(s.reviewDigest()).isEqualTo(BUNDLED_DIGEST);
            assertThat(s.criteria()).isEqualTo(PUBLIC_CRITERIA);
            assertThat(s.source()).isEqualTo(SOURCE);
        });
        assertThat(dataset.practiceVersions())
                .extracting(
                        ResearchDataset.PracticeVersion::reviewDefinition, ResearchDataset.PracticeVersion::standard)
                .containsExactlyInAnyOrder(
                        Tuple.tuple(ResearchDatasetAssembler.MATCHES_EXPORTING_BUNDLE, "small-pull-requests"),
                        Tuple.tuple(ResearchDatasetAssembler.NOT_MATCHED, null));
        String json = new String(JsonMapper.builder().build().writeValueAsBytes(dataset), StandardCharsets.UTF_8);
        assertThat(json)
                .contains(PUBLIC_CRITERIA)
                .doesNotContain(PRIVATE_CRITERIA)
                .doesNotContain(CUSTOM_DIGEST);

        // An older or edited version of a shipped practice is not presumed public.
        var drifted = ResearchDatasetAssembler.assemble(
                snapshot(Map.of()),
                UUID.randomUUID(),
                FROM,
                TO,
                Instant.parse("2036-10-01T00:00:00Z"),
                Map.of(
                        "small-pull-requests",
                        new ResearchDatasetAssembler.BundledStandard("v6:" + "d".repeat(64), PUBLIC_CRITERIA)),
                SOURCE,
                key(1));
        assertThat(drifted.dataset().standards()).isEmpty();
        assertThat(drifted.dataset().practiceVersions())
                .extracting(ResearchDataset.PracticeVersion::reviewDefinition)
                .containsOnly(ResearchDatasetAssembler.NOT_MATCHED);
    }

    @Test
    void shouldNeverSerialiseDatabaseIdentifiersOrOutsiders() {
        var assembled = assemble(key(1));
        String json =
                new String(JsonMapper.builder().build().writeValueAsBytes(assembled.dataset()), StandardCharsets.UTF_8);

        for (Object raw : List.of(
                review,
                legacy,
                bound,
                outsideWindow,
                outsiderResult,
                legacyProblem,
                complete,
                partial,
                unsupported,
                outsiderFeedback,
                job,
                PARTICIPANT,
                OUTSIDER,
                77_001L,
                CUSTOM_DIGEST,
                "private-custom",
                "free text")) {
            assertThat(json).as("raw value %s", raw).doesNotContain(raw.toString());
        }
        assertThat(json).contains("\"classification\":\"PSEUDONYMOUS_PERSONAL_DATA\"");
        assertThat(json).contains("\"severity\":null");
        assertThat(assembled.participantKeys()).containsOnlyKeys(PARTICIPANT, SECOND);
    }

    /** One review shared by A (result in the window) and B (support outside it, or an unjudged assignment). */
    private ResearchDatasetAssembler.Assembled sharedReview(Map<Long, Long> grants, ReviewSubjectRow subjectsOfReview) {
        var snapshot = new ResearchSnapshot(
                41L,
                Instant.parse("2026-10-20T06:00:00Z"),
                new ResearchQuestion("2026-10-04", "AET"),
                grants,
                List.of(new ResultRow(
                        bound,
                        review,
                        11L,
                        31L,
                        21L,
                        PARTICIPANT,
                        "MET",
                        null,
                        (short) 1,
                        true,
                        true,
                        AT,
                        null,
                        null,
                        null)),
                List.of(),
                List.of(new ReviewRow(review, "ITEM", "EVENT", "COMPLETE", AT, AT)),
                List.of(subjectsOfReview),
                List.of(new ReviewPracticeRow(review, 11L, 31L, "DONE", null, 1, true, 2, 1, 1, 0, 0)),
                Map.of(
                        review,
                        new ReviewUsageSnapshot(
                                new BigDecimal("0.0100"),
                                true,
                                1,
                                10,
                                5,
                                List.of(new ReviewUsageAttempt(
                                        job,
                                        0,
                                        1,
                                        10,
                                        5,
                                        0,
                                        0,
                                        0,
                                        new BigDecimal("0.0100"),
                                        UsagePricingState.PRICED,
                                        false)))),
                List.of(new ItemRow(21L, "scm.pull_request")),
                List.of(new PracticeRow(11L, null)),
                List.of(new PracticeVersionRow(31L, 11L, 1, CUSTOM_DIGEST)),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        return ResearchDatasetAssembler.assemble(
                snapshot, UUID.randomUUID(), FROM, TO, Instant.parse("2036-10-01T00:00:00Z"), Map.of(), SOURCE, key(1));
    }

    @Test
    void shouldBindEverySubjectOfSharedReviewFactsEvenWithoutOwnRowsInTheWindow() {
        var assembled = sharedReview(
                Map.of(PARTICIPANT, 77_001L, SECOND, 77_003L),
                new ReviewSubjectRow(review, 2, 0, 0, Set.of(PARTICIPANT, SECOND)));
        var dataset = assembled.dataset();

        assertThat(dataset.results()).as("only A has a row in the window").hasSize(1);
        assertThat(assembled.participantKeys())
                .as("B is in the protected mapping and the final grant check")
                .containsOnlyKeys(PARTICIPANT, SECOND);
        assertThat(dataset.participants()).hasSize(2);
        assertThat(dataset.reviews()).singleElement().satisfies(r -> {
            assertThat(r.subjectScope()).isEqualTo("ALL_SUBJECTS_PARTICIPATING");
            assertThat(r.subjectParticipants())
                    .containsExactlyInAnyOrder(
                            assembled.participantKeys().get(PARTICIPANT),
                            assembled.participantKeys().get(SECOND));
            assertThat(r.usage().state()).isEqualTo("RETAINED");
            assertThat(r.usage().allocation()).isEqualTo("WHOLE_REVIEW_NOT_ALLOCATED");
        });
    }

    @Test
    void shouldExposeNoOtherKeyAndNoSharedFactWhenASubjectIsNotAuthorisedOrUnknown() {
        var nonParticipant = sharedReview(
                Map.of(PARTICIPANT, 77_001L), new ReviewSubjectRow(review, 2, 0, 0, Set.of(PARTICIPANT, SECOND)));
        assertThat(nonParticipant.participantKeys()).containsOnlyKeys(PARTICIPANT);
        assertThat(nonParticipant.dataset().reviews()).singleElement().satisfies(r -> {
            assertThat(r.subjectScope()).isEqualTo("INCLUDES_NON_PARTICIPANT");
            assertThat(r.subjectParticipants()).isEmpty();
            assertThat(r.usage().calls()).isNull();
            assertThat(r.practices().getFirst().assignments()).isNull();
        });

        // An unjudged assignment with no usable recorded developer: unknown, never filled in.
        var unknown = sharedReview(
                Map.of(PARTICIPANT, 77_001L, SECOND, 77_003L),
                new ReviewSubjectRow(review, 2, 1, 0, Set.of(PARTICIPANT)));
        assertThat(unknown.participantKeys()).containsOnlyKeys(PARTICIPANT);
        assertThat(unknown.dataset().reviews()).singleElement().satisfies(r -> {
            assertThat(r.subjectScope()).isEqualTo("ATTRIBUTION_UNKNOWN");
            assertThat(r.subjectParticipants()).isEmpty();
            assertThat(r.usage().state()).isEqualTo("UNAVAILABLE");
        });
    }

    @Test
    void shouldDeriveKeysPerExportAndPerRecordKind() {
        var first = assemble(key(1));
        var second = assemble(key(2));
        String participant = first.participantKeys().get(PARTICIPANT);

        assertThat(participant).matches("^p_[a-z2-7]{26}$");
        assertThat(assemble(key(1)).participantKeys()).containsEntry(PARTICIPANT, participant);
        assertThat(second.participantKeys().get(PARTICIPANT)).isNotEqualTo(participant);
        var keys = key(3);
        assertThat(keys.key(ResearchPseudonyms.Kind.PRACTICE, 11L).substring(2))
                .as("the same id in two domains gets unrelated keys")
                .isNotEqualTo(keys.key(ResearchPseudonyms.Kind.ITEM, 11L).substring(2));
    }
}
