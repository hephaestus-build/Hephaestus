package de.tum.cit.aet.hephaestus.agent.context.providers.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptHistory;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptHistory.RetainedAttempt;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobInput;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemLabel;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSourceAccess;
import de.tum.cit.aet.hephaestus.practices.result.PracticeAssessments;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ReviewJobFixtures;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class ReviewAttemptsContentSourceTest extends BaseUnitTest {
    private static final ReviewAssignmentId ASSIGNMENT = new ReviewAssignmentId(RuntimeReviewTestData.REVIEW, 31, 2);

    private final ReviewAttemptHistory jobs = mock(ReviewAttemptHistory.class);
    private final ReviewAssignmentQuery assignments = mock(ReviewAssignmentQuery.class);
    private final ItemSourceAccess source = mock(ItemSourceAccess.class);
    private final RegisteredItemLabels labels = mock(RegisteredItemLabels.class);

    private final PracticeAssessments assessments = new PracticeAssessments(
            mock(ResultHistoryQuery.class), assignments, labels, mock(DeveloperPracticeStandings.class));

    private ReviewAttemptsContentSource attempts() {
        when(source.kinds()).thenReturn(Set.of(RuntimeReviewTestData.ITEM.kind()));
        when(source.permits(RuntimeReviewTestData.ITEM, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(true);
        return new ReviewAttemptsContentSource(
                jobs, assignments, assessments, List.of(source), labels, new JsonMapper());
    }

    private final Map<UUID, List<ReviewAssignmentQuery.Retained>> attribution = new HashMap<>();

    private void listed(ReviewJob... retained) {
        when(jobs.recent(eq(1L), any(), any()))
                .thenReturn(Arrays.stream(retained)
                        .map(ReviewAttemptsContentSourceTest::attempt)
                        .toList());
    }

    private void attributed(ReviewJob job, List<ReviewAssignmentQuery.Retained> retained) {
        attribution.put(job.getId(), retained);
        when(assignments.retained(eq(1L), anyCollection())).thenAnswer(call -> {
            Map<UUID, List<ReviewAssignmentQuery.Retained>> answered = new HashMap<>();
            for (ReviewAssignmentQuery.RetainedRun run :
                    call.<Collection<ReviewAssignmentQuery.RetainedRun>>getArgument(1))
                if (attribution.containsKey(run.jobId())) answered.put(run.jobId(), attribution.get(run.jobId()));
            return answered;
        });
    }

    private static RetainedAttempt attempt(ReviewJob job) {
        return new RetainedAttempt(
                job.getId(),
                job.getReviewId(),
                job.getStatus(),
                job.getAttempt(),
                job.getProcessingStatus(),
                job.getAvailableAt(),
                job.getStartedAt(),
                job.getCompletedAt(),
                job.getInput());
    }

    private static ReviewAssignmentQuery.Retained about(@Nullable Long developer) {
        return new ReviewAssignmentQuery.Retained(ASSIGNMENT, RuntimeReviewTestData.ITEM, developer);
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewJobStatus.class,
            names = {"QUEUED", "RUNNING", "FAILED"})
    void shouldShowAnUnfinishedOrFailedInvocationWithoutInventingAResult(ReviewJobStatus status) {
        var job = RuntimeReviewTestData.job();
        ReviewJobFixtures.withStatus(job, status);
        listed(job);
        attributed(job, List.of(about(5L)));
        when(labels.labels(eq(1L), any()))
                .thenReturn(Map.of(
                        2L,
                        new ItemLabel(2, "Guide", "Onboarding guide", "document", 69, null, "https://docs/x", null)));
        var attempts = attempts();
        var payload = attempts.overview(1, 5);
        var row = payload.path("attempts").get(0);
        assertThat(row.path("status").asString()).isEqualTo(status.name());
        assertThat(row.path("jobId").asString()).isEqualTo(job.getId().toString());
        assertThat(row.has("resultId")).isFalse();
        assertThat(row.has("outcome")).isFalse();
        assertThat(row.has("evidence")).isFalse();
        assertThat(row.at("/work/number").intValue())
                .as("the same path that names the work in results_history.json")
                .isEqualTo(69);
        assertThat(row.at("/work/title").asString()).isEqualTo("Onboarding guide");
        assertThat(row.at("/work/url").asString()).isEqualTo("https://docs/x");
        assertThat(row.has("reviewsResource"))
                .as("a document has no review attempts resource")
                .isFalse();
        assertThat(payload.path("coverage").path("attributionComplete").asBoolean())
                .isTrue();
        assertThat(payload.path("coverage").has("attributionNote")).isFalse();
        assertThat(payload.path("about").asString()).contains("not reviews by people");
        when(source.permits(RuntimeReviewTestData.ITEM, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(false);
        assertThat(attempts.overview(1, 5).path("attempts"))
                .as("the source no longer permits conversation use on the reviewed work")
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldStateThePersistedReviewStatusBesideTheRetainedJobStatus(ReviewStatus status) {
        var waiting = RuntimeReviewTestData.job();
        ReviewJobFixtures.withStatus(
                waiting,
                switch (status) {
                    case QUEUED -> ReviewJobStatus.QUEUED;
                    case RUNNING -> ReviewJobStatus.RUNNING;
                    case COMPLETE, PARTIAL -> ReviewJobStatus.COMPLETED;
                    case FAILED -> ReviewJobStatus.FAILED;
                    case CANCELLED -> ReviewJobStatus.CANCELLED;
                });
        attributed(waiting, List.of(about(5L)));
        listed(waiting);
        when(labels.labels(eq(1L), any())).thenReturn(Map.of());
        var reviewed = new ReviewAssignmentQuery.ReviewedItem(RuntimeReviewTestData.REVIEW, ASSIGNMENT.itemId());
        when(assignments.recordedForDeveloper(1L, 5L, Set.of(reviewed)))
                .thenReturn(Map.of(reviewed, new ReviewAssignmentQuery.Recorded(status, null, null)));

        var row = attempts().overview(1, 5).path("attempts").path(0);

        assertThat(row.path("status").asString()).isEqualTo(waiting.getStatus().name());
        assertThat(row.path("reviewStatus").asString()).isEqualTo(status.name());
    }

    @Test
    void shouldShowCouldNotCheckWithoutAnOutcomeWhenCaptureFails() {
        var ref = new ItemRef(1, ASSIGNMENT.itemId(), new ItemKind("scm.pull_request"), 42L, null, null);
        var run = RuntimeReviewTestData.job();
        ReviewJobFixtures.withStatus(run, ReviewJobStatus.FAILED);
        attributed(run, List.of(new ReviewAssignmentQuery.Retained(ASSIGNMENT, ref, 5L)));
        listed(run);
        when(source.kinds()).thenReturn(Set.of(ref.kind()));
        when(source.permits(ref, SourceUsePurpose.CONVERSATIONAL_MENTORING)).thenReturn(true);
        var reviewed = new ReviewAssignmentQuery.ReviewedItem(run.getReviewId(), ASSIGNMENT.itemId());
        when(assignments.recordedForDeveloper(1L, 5L, Set.of(reviewed)))
                .thenReturn(Map.of(
                        reviewed,
                        new ReviewAssignmentQuery.Recorded(
                                ReviewStatus.FAILED,
                                ReviewStopReason.PREPARATION_FAILED,
                                new ReviewAssignmentQuery.AssignmentCounts(1, 0, 0, 1, 0))));
        var reader = new ReviewAttemptsContentSource(
                jobs, assignments, assessments, List.of(source), labels, new JsonMapper());
        var detail = reader.read(1, 5, "inputs/context/review_attempts/pull_request/42.json")
                .orElseThrow();

        for (var payload : List.of(reader.overview(1, 5), detail)) {
            var row = payload.path("attempts").path(0);
            assertThat(row.path("checkStatus").asString()).isEqualTo("COULD_NOT_CHECK");
            assertThat(row.has("outcome")).isFalse();
            assertThat(row.has("problems")).isFalse();
        }
    }

    @Test
    void shouldKeepACompletedPracticeWhenAnotherPracticeCouldNotRun() {
        var run = RuntimeReviewTestData.job();
        ReviewJobFixtures.withStatus(run, ReviewJobStatus.COMPLETED);
        attributed(run, List.of(about(5L)));
        listed(run);
        var reviewed = new ReviewAssignmentQuery.ReviewedItem(run.getReviewId(), ASSIGNMENT.itemId());
        when(assignments.recordedForDeveloper(1L, 5L, Set.of(reviewed)))
                .thenReturn(Map.of(
                        reviewed,
                        new ReviewAssignmentQuery.Recorded(
                                ReviewStatus.PARTIAL,
                                ReviewStopReason.READINESS_REFUSED,
                                new ReviewAssignmentQuery.AssignmentCounts(2, 1, 0, 1, 0))));

        var row = attempts().overview(1, 5).path("attempts").path(0);
        assertThat(row.path("assignmentCounts").path("judged").asInt()).isEqualTo(1);
        assertThat(row.has("checkStatus")).isFalse();
    }

    @Test
    void shouldStateHowEachRunsReviewEndedFromOneReadAndKeepARunWhoseReviewCannotBeRead() {
        var first = RuntimeReviewTestData.job();
        var retry = RuntimeReviewTestData.job();
        var unreadable = RuntimeReviewTestData.job();
        unreadable.setReviewId(UUID.randomUUID());
        for (var job : List.of(first, retry, unreadable)) {
            ReviewJobFixtures.withStatus(job, ReviewJobStatus.COMPLETED);
            attributed(job, List.of(about(5L)));
        }
        listed(first, retry, unreadable);
        when(labels.labels(eq(1L), any())).thenReturn(Map.of());
        var reviewed = new ReviewAssignmentQuery.ReviewedItem(RuntimeReviewTestData.REVIEW, ASSIGNMENT.itemId());
        when(assignments.recordedForDeveloper(
                        1L,
                        5L,
                        Set.of(
                                reviewed,
                                new ReviewAssignmentQuery.ReviewedItem(unreadable.getReviewId(), ASSIGNMENT.itemId()))))
                .thenReturn(Map.of(
                        reviewed,
                        new ReviewAssignmentQuery.Recorded(
                                ReviewStatus.PARTIAL, ReviewStopReason.EXECUTION_INCOMPLETE, null)));

        var rows = attempts().overview(1, 5).path("attempts");

        assertThat(rows)
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.path("status").asString()).isEqualTo("COMPLETED"));
        assertThat(List.of(rows.path(0), rows.path(1))).allSatisfy(row -> {
            assertThat(row.path("reviewStatus").asString()).isEqualTo("PARTIAL");
            assertThat(row.path("reviewStopReason").asString()).isEqualTo("EXECUTION_INCOMPLETE");
            assertThat(row.has("assignmentCounts"))
                    .as("counts that could miss the developer's own are left out, not stated as zero")
                    .isFalse();
        });
        assertThat(rows.path(2).path("jobId").asString())
                .isEqualTo(unreadable.getId().toString());
        assertThat(rows.path(2).has("reviewStatus"))
                .as("the run stays listed; its review's end is not guessed from its own state")
                .isFalse();
        verify(assignments, times(1)).recordedForDeveloper(anyLong(), anyLong(), any());
    }

    @Test
    void shouldKeepTheContextAndSayItIsIncompleteWhenARetainedRunCannotBeAttributed() {
        var own = RuntimeReviewTestData.job();
        var retired = RuntimeReviewTestData.job();
        var partial = RuntimeReviewTestData.job();
        partial.setInput(new ReviewJobInput(
                2, List.of(ASSIGNMENT, new ReviewAssignmentId(RuntimeReviewTestData.REVIEW, 32, 2))));
        listed(retired, own, partial);
        attributed(own, List.of(about(5L)));
        attributed(retired, List.of());
        attributed(partial, List.of(about(5L)));
        when(labels.labels(eq(1L), any())).thenReturn(Map.of());

        var payload = attempts().overview(1, 5);

        assertThat(payload.path("attempts")).hasSize(1);
        assertThat(payload.path("attempts").get(0).path("jobId").asString())
                .as("the developer's attributable run is still shown")
                .isEqualTo(own.getId().toString());
        assertThat(payload.path("coverage").path("attributionComplete").asBoolean())
                .as("a run with no readable or only partly readable attribution is left out, and coverage says so")
                .isFalse();
        assertThat(payload.path("coverage").path("attributionNote").asString()).contains("left out");
    }

    @Test
    void shouldNotShowOrCountRunsAttributedToAnotherDeveloperOrToNobody() {
        var foreign = RuntimeReviewTestData.job();
        ReviewJobFixtures.withStatus(foreign, ReviewJobStatus.FAILED);
        var nobody = RuntimeReviewTestData.job();
        listed(foreign, nobody);
        attributed(foreign, List.of(about(6L)));
        attributed(nobody, List.of(about(null)));

        // No source is consulted: the developer filter comes first.
        var payload = new ReviewAttemptsContentSource(
                        jobs, assignments, assessments, List.of(source), labels, new JsonMapper())
                .overview(1, 5);

        assertThat(payload.path("attempts")).isEmpty();
        assertThat(payload.path("coverage").path("attributionComplete").asBoolean())
                .as("a failure of another developer is attributed, so it is neither shown nor counted as unknown")
                .isTrue();
        assertThat(payload.toString()).doesNotContain(foreign.getId().toString());
    }

    @Test
    void shouldSayAnItemsRunsAreUnavailableRatherThanAbsentWhenARunCannotBeAttributed() {
        var unknown = RuntimeReviewTestData.job();
        listed(unknown);
        attributed(unknown, List.of());
        var attempts = new ReviewAttemptsContentSource(
                jobs, assignments, assessments, List.of(source), labels, new JsonMapper());

        var answer = attempts.read(1, 5, "inputs/context/review_attempts/pull_request/42.json");

        assertThat(answer).hasValueSatisfying(node -> {
            assertThat(node.path("status").asString())
                    .as("an unattributed run could be this item's, so absence is not claimed")
                    .isEqualTo("UNAVAILABLE");
            assertThat(node.path("attempts")).isEmpty();
            assertThat(node.toString()).doesNotContain(unknown.getId().toString());
        });
    }

    @Test
    void shouldAnswerOnlyItsOwnItemPathsAndSayNotFoundForWorkWithoutAttempts() {
        when(jobs.recent(eq(1L), any(), any())).thenReturn(List.of());
        var attempts =
                new ReviewAttemptsContentSource(jobs, assignments, assessments, List.of(), labels, new JsonMapper());

        assertThat(attempts.read(1, 5, "inputs/context/review_attempts/pull_request/42.json"))
                .hasValueSatisfying(
                        node -> assertThat(node.path("status").asString()).isEqualTo("NOT_FOUND"));
        assertThat(attempts.read(1, 5, "inputs/context/merge_readiness/42.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.resourceOf(new ItemKind("scm.issue"), 21))
                .isEqualTo("inputs/context/review_attempts/issue/21.json");
    }

    @Test
    void shouldParseOnlyExplicitItemResourceKeys() {
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/issue/21.json"))
                .contains(new ReviewAttemptsContentSource.ItemSelection(new ItemKind("scm.issue"), 21));
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/pull_request/42.json"))
                .contains(new ReviewAttemptsContentSource.ItemSelection(new ItemKind("scm.pull_request"), 42));
        assertThat(ReviewAttemptsContentSource.selectionOf("../issue/21.json")).isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf(
                        "inputs/context/review_attempts/issue/9223372036854775807.json"))
                .contains(new ReviewAttemptsContentSource.ItemSelection(new ItemKind("scm.issue"), Long.MAX_VALUE));
        assertThat(ReviewAttemptsContentSource.selectionOf(
                        "inputs/context/review_attempts/issue/9223372036854775808.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/issue/0.json"))
                .isEmpty();
    }
}
