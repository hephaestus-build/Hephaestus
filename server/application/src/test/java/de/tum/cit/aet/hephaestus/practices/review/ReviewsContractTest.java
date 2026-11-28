package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemRefDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.ReviewFeedbackCountsDTO;
import de.tum.cit.aet.hephaestus.practices.model.PracticeEditedIn;
import de.tum.cit.aet.hephaestus.practices.review.dto.RepositoryCommitRequestItemDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.RepositoryCommitVersionDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewAudienceDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewChangeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewCoverageDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDetailDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewDifference;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewEvidenceDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewEvidenceState;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewItemCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewProgressDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewResultCountsDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSetupRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewStage;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewUsageDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewedVersionDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.SubmittedVersionDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.SubmittedVersionRequestItemDTO;
import de.tum.cit.aet.hephaestus.practices.review.schedule.dto.ReviewScheduleDTO;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeLevel;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCadence;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewVersionKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Reviews contract of ADR 0167 as JSON: the request and the read model of a requested and a scheduled review, for a
 * repository at a commit and for a submitted version. {@code reviews-contract.json} is what the request writer and the
 * project page build against. A change to it is a change of the contract.
 */
@Tag("unit")
class ReviewsContractTest {
    private static final String FIXTURE = "/contracts/reviews/reviews-contract.json";

    /** As the application's mapper (application.yml): absent values are left out unless a DTO always includes them. */
    private static final JsonMapper JSON = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private static final UUID SETUP = UUID.fromString("5e7a0000-0000-4000-8000-000000000001");
    private static final UUID TEAM = UUID.fromString("7ea30000-0000-4000-8000-000000000002");
    private static final UUID VERSION = UUID.fromString("0e510000-0000-4000-8000-000000000003");
    private static final ReviewSetupRefDTO TEAM_SETUP = new ReviewSetupRefDTO(SETUP, "Team practices");
    private static final ReviewAudienceDTO TEAM_AUDIENCE = new ReviewAudienceDTO(ReviewAudience.TEAM, TEAM);
    private static final UUID NEXT_VERSION = UUID.fromString("0e510000-0000-4000-8000-000000000006");
    private static final String COMMIT = "4f9c2e1b7a3d5e6f8091a2b3c4d5e6f708192a3b";

    @Test
    void shouldSerializeTheRequestAndTheReadModelOfEachKindOfItemAndOriginAsTheFixtureHoldsThem() throws IOException {
        // Parsed back from its text, as a client reads it: a tree built from values keeps a long as a long node.
        var actual = JSON.readTree(JSON.writeValueAsString(contract()));
        var expected = fixture();
        if (!actual.equals(expected)) {
            // The new shape beside the build, to compare with the fixture or to replace it after a reviewed change.
            var written = Path.of("build", "contracts", "reviews-contract.actual.json");
            Files.createDirectories(Objects.requireNonNull(written.getParent()));
            Files.writeString(written, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(actual));
        }
        // A JsonNode is also an Iterable, so AssertJ cannot choose an assertion for it; the trees compare by equals.
        assertThat(actual.equals(expected))
                .as("the contract as serialized, beside the build")
                .isTrue();
    }

    @Test
    void shouldReadEachRequestOfTheFixtureAsItsKindOfItem() throws IOException {
        var requests = fixture().get("requests");
        var commit = JSON.treeToValue(requests.get("repositoryCommit"), ReviewRequestDTO.class);
        var version = JSON.treeToValue(requests.get("submittedVersion"), ReviewRequestDTO.class);

        assertThat(commit.item()).isInstanceOf(RepositoryCommitRequestItemDTO.class);
        assertThat(version.item()).isEqualTo(new SubmittedVersionRequestItemDTO("SUBMITTED_VERSION", VERSION));
        JsonNode written = JSON.readTree(JSON.writeValueAsString(commit));
        assertThat(written.equals(requests.get("repositoryCommit"))).isTrue();
    }

    /** The fixture's reviews are closed and none is a trial, so these hold for every entry, as on the server. */
    @Test
    void shouldHoldOnlyValuesThatAClosedReviewCanProduce() throws IOException {
        List<String> impossible = new ArrayList<>();
        for (var schedule : fixture().get("schedules"))
            if (!schedule.get("itemKind").asString().equals("scm.repository"))
                impossible.add("schedule itemKind " + schedule.get("itemKind"));
        for (var review : fixture().get("reviews").properties()) {
            var summary = review.getValue().get("summary");
            var kind = summary.get("item").get("kind").asString();
            // A board is a submitted item that no adapter registers yet.
            if (!List.of("scm.repository", "scm.pull_request", "figma.board").contains(kind))
                impossible.add(review.getKey() + " item kind " + kind);
            if (summary.get("kind").asString().equals("SCAN")
                    && !summary.get("progress").isNull()) impossible.add(review.getKey() + ": a scan has no progress");
            for (var outcome : List.of("met", "notMet", "doesNotApply", "couldNotDetermine")) {
                int sum = 0;
                for (var practice : summary.get("practices"))
                    sum += practice.get("results").get(outcome).asInt();
                if (sum != summary.get("results").get(outcome).asInt())
                    impossible.add(review.getKey() + " results." + outcome + " is not the sum of its practices");
            }
            for (var coverage : summary.get("coverage"))
                impossible.addAll(
                        impossible(review.getKey() + " " + coverage.get("level").asString(), coverage.get("counts")));
            for (var practice : summary.get("practices"))
                impossible.addAll(impossible(
                        review.getKey() + " " + practice.get("practiceSlug").asString(), practice.get("itemCounts")));
        }
        assertThat(impossible).isEmpty();
    }

    /**
     * In a closed review that is no trial, each planned pair is reviewed, withheld or not reviewed, and a reused pair
     * is one of the reviewed ones.
     */
    private static List<String> impossible(String where, JsonNode counts) {
        int accounted = counts.get("reviewed").asInt()
                + counts.get("withheld").asInt()
                + counts.get("notReviewed").asInt();
        boolean possible = counts.get("listed").asInt() == accounted
                && counts.get("reused").asInt() <= counts.get("reviewed").asInt();
        return possible ? List.of() : List.of(where + ": " + counts);
    }

    @Test
    void shouldRefuseARequestItemOfAnUnknownKind() {
        var body = "{\"setupId\":\"" + SETUP + "\",\"requestKey\":\"" + UUID.randomUUID()
                + "\",\"item\":{\"kind\":\"ITEM_REVISION\",\"itemId\":\"7\"}}";

        assertThatThrownBy(() -> JSON.readValue(body, ReviewRequestDTO.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void shouldNameATeamProjectOnlyForATeamAudience() {
        assertThatThrownBy(() -> new ReviewAudienceDTO(ReviewAudience.TEAM, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewAudienceDTO(ReviewAudience.OPERATIONAL, TEAM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKnowTheVersionAndTheSetupOfEveryComparison() {
        var baseline = UUID.randomUUID();

        assertThatThrownBy(() -> new ReviewChangeDTO(
                        baseline, ReviewDifference.UNKNOWN, ReviewDifference.SAME, ReviewDifference.SAME))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewChangeDTO(
                        baseline, ReviewDifference.SAME, ReviewDifference.SAME, ReviewDifference.UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldSumCoverageWithinEachLevelAndNeverAcrossLevels() {
        var files = practice("tests-cover-changes", PracticeLevel.MEMBER, counts(40, 36, 6, 1, 3));
        var moreFiles = practice("no-secrets-in-code", PracticeLevel.MEMBER, counts(40, 40, 0, 0, 0));
        var repository = practice("ci-runs-the-tests", PracticeLevel.ITEM, counts(1, 1, 0, 0, 0));

        assertThat(ReviewSummaryQueryService.coverage(List.of(files, repository, moreFiles)))
                .containsExactly(
                        new ReviewCoverageDTO(PracticeLevel.ITEM, counts(1, 1, 0, 0, 0)),
                        new ReviewCoverageDTO(PracticeLevel.MEMBER, counts(80, 76, 6, 1, 3)));
    }

    @Test
    void shouldReadAnOlderScanOrTrialAsOperationalAndAnyOtherReviewAsPersonalButNeverTeam() {
        assertThat(ReviewAudience.ofOlder(ReviewKind.SCAN, ReviewOrigin.SCHEDULE))
                .isEqualTo(ReviewAudience.OPERATIONAL);
        assertThat(ReviewAudience.ofOlder(ReviewKind.ITEM, ReviewOrigin.TRIAL)).isEqualTo(ReviewAudience.OPERATIONAL);
        assertThat(ReviewAudience.ofOlder(ReviewKind.ITEM, ReviewOrigin.EVENT)).isEqualTo(ReviewAudience.PERSONAL);
        assertThat(ReviewVersionKind.ofOlder(ReviewKind.SCAN)).isEqualTo(ReviewVersionKind.REPOSITORY_COMMIT);
        assertThat(PracticeLevel.ofOlder(ReviewKind.SCAN)).isEqualTo(PracticeLevel.MEMBER);
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream in = Objects.requireNonNull(ReviewsContractTest.class.getResourceAsStream(FIXTURE))) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, Object> contract() {
        var repositoryRequest = new ReviewRequestDTO(
                SETUP,
                new RepositoryCommitRequestItemDTO("REPOSITORY_COMMIT", "4711", "main", COMMIT),
                UUID.fromString("4e9e0000-0000-4000-8000-000000000004"));
        var versionRequest = new ReviewRequestDTO(
                SETUP,
                new SubmittedVersionRequestItemDTO("SUBMITTED_VERSION", VERSION),
                UUID.fromString("4e9e0000-0000-4000-8000-000000000005"));
        var requested = repositoryReview(
                "aaaa0000-0000-4000-8000-000000000011", ReviewOrigin.REQUEST, ReviewStatus.COMPLETE, null);
        var scheduled = repositoryReview(
                "aaaa0000-0000-4000-8000-000000000012",
                ReviewOrigin.SCHEDULE,
                ReviewStatus.PARTIAL,
                new ReviewChangeDTO(
                        requested.summary().id(),
                        ReviewDifference.SAME,
                        ReviewDifference.CHANGED,
                        ReviewDifference.SAME));
        Map<String, Object> reviews = new LinkedHashMap<>();
        reviews.put("repositoryCommitRequest", requested);
        reviews.put("repositoryCommitSchedule", scheduled);
        var submitted = versionReview("bbbb0000-0000-4000-8000-000000000021", ReviewOrigin.REQUEST, VERSION, 2, null);
        reviews.put("submittedVersionRequest", submitted);
        reviews.put(
                "submittedVersionSchedule",
                versionReview(
                        "bbbb0000-0000-4000-8000-000000000022",
                        ReviewOrigin.SCHEDULE,
                        NEXT_VERSION,
                        3,
                        new ReviewChangeDTO(
                                submitted.summary().id(),
                                ReviewDifference.CHANGED,
                                ReviewDifference.SAME,
                                ReviewDifference.SAME)));
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("requests", Map.of("repositoryCommit", repositoryRequest, "submittedVersion", versionRequest));
        reviews.put("itemRevisionOlder", olderItemReview());
        contract.put("reviews", reviews);
        contract.put("schedules", List.of(recurringCheck()));
        return contract;
    }

    /** A team's review of one repository commit: file practices and a repository-level CI practice. */
    private static ReviewCase repositoryReview(
            String id, ReviewOrigin origin, ReviewStatus status, @Nullable ReviewChangeDTO change) {
        boolean partial = status == ReviewStatus.PARTIAL;
        var practices = List.of(
                practice(
                        "tests-cover-changes",
                        PracticeLevel.MEMBER,
                        counts(40, partial ? 37 : 40, 6, 0, partial ? 3 : 0),
                        new ReviewResultCountsDTO(partial ? 30 : 33, 4, 2, 1)),
                practice("ci-runs-the-tests", PracticeLevel.ITEM, counts(1, 1, 0, 0, 0)));
        var repository =
                new ItemRefDTO("4711", "scm.repository", null, null, "acme/shop", "acme/shop", null, null, null, null);
        return detail(
                id,
                ReviewKind.SCAN,
                repository,
                COMMIT,
                origin,
                status,
                ReviewVersionKind.REPOSITORY_COMMIT,
                new RepositoryCommitVersionDTO("REPOSITORY_COMMIT", "main", COMMIT),
                practices,
                change,
                List.of(
                        new ReviewEvidenceDTO("ci.configured", ReviewEvidenceState.PRESENT, created(origin)),
                        new ReviewEvidenceDTO("ci.observed", ReviewEvidenceState.PRESENT, created(origin)),
                        new ReviewEvidenceDTO("merge-rule", ReviewEvidenceState.MISSING, null)),
                TEAM_SETUP,
                TEAM_AUDIENCE);
    }

    /** A request on the first day; the setup's scheduled review of the same commit on the next. */
    private static Instant created(ReviewOrigin origin) {
        return Instant.parse(origin == ReviewOrigin.SCHEDULE ? "2026-12-02T09:00:00Z" : "2026-12-01T09:00:00Z");
    }

    /** A team's review of one submitted version of a board. */
    private static ReviewCase versionReview(
            String id, ReviewOrigin origin, UUID version, int number, @Nullable ReviewChangeDTO change) {
        var board = new ItemRefDTO(
                "9001", "figma.board", null, null, "Checkout flow", "Checkout flow", null, null, null, null);
        return detail(
                id,
                ReviewKind.ITEM,
                board,
                version.toString(),
                origin,
                ReviewStatus.COMPLETE,
                ReviewVersionKind.SUBMITTED_VERSION,
                new SubmittedVersionDTO("SUBMITTED_VERSION", version, number),
                List.of(practice("flows-name-their-goal", PracticeLevel.ITEM, counts(1, 1, 0, 0, 0))),
                change,
                List.of(),
                TEAM_SETUP,
                TEAM_AUDIENCE);
    }

    /** An event review of a pull request from before reviews stored a setup or an audience. */
    private static ReviewCase olderItemReview() {
        var pullRequest = new ItemRefDTO(
                "5150",
                "scm.pull_request",
                "4711",
                null,
                "acme/shop#42",
                "Pay with a saved card",
                null,
                null,
                null,
                null);
        return detail(
                "cccc0000-0000-4000-8000-000000000031",
                ReviewKind.ITEM,
                pullRequest,
                COMMIT,
                ReviewOrigin.EVENT,
                ReviewStatus.COMPLETE,
                ReviewVersionKind.ITEM_REVISION,
                null,
                List.of(practice("small-pull-requests", PracticeLevel.ITEM, counts(1, 1, 0, 0, 0))),
                null,
                List.of(),
                null,
                new ReviewAudienceDTO(ReviewAudience.PERSONAL, null));
    }

    /** Today's recurring check, which belongs to no setup. */
    private static ReviewScheduleDTO recurringCheck() {
        return new ReviewScheduleDTO(
                "scm.repository",
                ReviewCadence.WEEKLY,
                null,
                true,
                "25.00",
                UUID.fromString("dddd0000-0000-4000-8000-000000000041"),
                "3",
                Instant.parse("2026-12-07T02:00:00Z"),
                Instant.parse("2026-11-30T02:00:00Z"),
                null,
                Instant.parse("2026-11-02T10:00:00Z"),
                Instant.parse("2026-11-02T10:00:00Z"),
                null);
    }

    /** One review as its history entry and as its detail. */
    private record ReviewCase(ReviewSummaryDTO summary, ReviewDetailDTO detail) {}

    private static ReviewCase detail(
            String id,
            ReviewKind kind,
            ItemRefDTO item,
            String itemVersion,
            ReviewOrigin origin,
            ReviewStatus status,
            ReviewVersionKind versionKind,
            @Nullable ReviewedVersionDTO reviewedVersion,
            List<ReviewPracticeDTO> practices,
            @Nullable ReviewChangeDTO change,
            List<ReviewEvidenceDTO> evidence,
            @Nullable ReviewSetupRefDTO setup,
            ReviewAudienceDTO audience) {
        var summary = new ReviewSummaryDTO(
                UUID.fromString(id),
                kind,
                item,
                itemVersion,
                origin,
                status,
                created(origin),
                created(origin).plusSeconds(1_200),
                null,
                null,
                created(origin),
                status == ReviewStatus.PARTIAL ? ReviewStopReason.BUDGET : null,
                practices,
                total(practices),
                ReviewFeedbackCountsDTO.from(Map.of()),
                practices.size(),
                0,
                "review-model",
                // The server reports the progress of an item review only (ReviewProgressService).
                kind == ReviewKind.SCAN
                        ? null
                        : new ReviewProgressDTO(practices.size(), practices.size(), false, ReviewStage.READY),
                setup,
                versionKind,
                audience,
                reviewedVersion,
                ReviewSummaryQueryService.coverage(practices),
                change);
        return new ReviewCase(
                summary,
                new ReviewDetailDTO(
                        summary.id(),
                        summary.kind(),
                        summary.item(),
                        summary.itemVersion(),
                        summary.origin(),
                        summary.status(),
                        summary.createdAt(),
                        summary.completedAt(),
                        summary.startedBy(),
                        summary.batchId(),
                        summary.orderedAt(),
                        summary.stopReason(),
                        summary.practices(),
                        summary.results(),
                        summary.feedback(),
                        summary.jobCount(),
                        summary.activeJobCount(),
                        summary.model(),
                        null,
                        new ReviewUsageDTO("0.42", true, false, 12, 48_000, 3_200, List.of()),
                        null,
                        summary.progress(),
                        summary.setup(),
                        summary.versionKind(),
                        summary.audience(),
                        summary.reviewedVersion(),
                        summary.coverage(),
                        summary.change(),
                        evidence));
    }

    /** A practice whose every reviewed pair is Met. */
    private static ReviewPracticeDTO practice(String slug, PracticeLevel level, ReviewItemCountsDTO counts) {
        return practice(slug, level, counts, new ReviewResultCountsDTO(counts.reviewed(), 0, 0, 0));
    }

    private static ReviewPracticeDTO practice(
            String slug, PracticeLevel level, ReviewItemCountsDTO counts, ReviewResultCountsDTO results) {
        return new ReviewPracticeDTO(
                slug,
                slug,
                PracticeEditedIn.HEPHAESTUS,
                "1",
                1,
                ReviewPracticeStatus.DONE,
                null,
                counts,
                results,
                null,
                level);
    }

    /** The outcomes of a review, the sum of its practices' outcomes. */
    private static ReviewResultCountsDTO total(List<ReviewPracticeDTO> practices) {
        int met = 0;
        int notMet = 0;
        int doesNotApply = 0;
        int couldNotDetermine = 0;
        for (var practice : practices) {
            met += practice.results().met();
            notMet += practice.results().notMet();
            doesNotApply += practice.results().doesNotApply();
            couldNotDetermine += practice.results().couldNotDetermine();
        }
        return new ReviewResultCountsDTO(met, notMet, doesNotApply, couldNotDetermine);
    }

    private static ReviewItemCountsDTO counts(int listed, int reviewed, int reused, int withheld, int notReviewed) {
        return new ReviewItemCountsDTO(listed, reviewed, reused, withheld, notReviewed);
    }
}
