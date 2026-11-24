package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.practices.spi.AssessmentPort;
import de.tum.cit.aet.hephaestus.practices.spi.DeveloperPracticeStandings;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery.AssignmentCounts;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class PracticeAssessmentsTest extends BaseUnitTest {
    private final ResultHistoryQuery history = mock(ResultHistoryQuery.class);
    private final ReviewAssignmentQuery assignments = mock(ReviewAssignmentQuery.class);
    private final PracticeAssessments port = new PracticeAssessments(
            history, assignments, mock(RegisteredItemLabels.class), mock(DeveloperPracticeStandings.class));

    @Test
    void shouldReportMoreHistoryWhenUnreadableRowsAtTheLimitAreOmitted() {
        var unreadable = mock(ResultHistoryEntry.class);
        var since = Instant.parse("2026-11-01T00:00:00Z");
        when(history.forDeveloper(1, 2, since, 2)).thenReturn(List.of(unreadable, unreadable));

        assertThat(port.observations(1, 2, since, 1))
                .isEqualTo(new AssessmentPort.ObservationHistory(List.of(), 0, true));
    }

    @Test
    void shouldReportAWithheldObservationWhenTheHistoryHasNoMoreRows() {
        var withheld = mock(ResultHistoryEntry.class);
        when(withheld.developerAccountId()).thenReturn(2L);
        when(withheld.item()).thenReturn(new ItemRef(1, 41, new ItemKind("scm.pull_request"), 421L, null, null));
        when(withheld.origin()).thenReturn(ReviewOrigin.REQUEST);
        when(history.forDeveloper(1, 2, Instant.EPOCH, 2)).thenReturn(List.of(withheld));

        assertThat(port.observations(1, 2, Instant.EPOCH, 1))
                .isEqualTo(new AssessmentPort.ObservationHistory(List.of(), 1, false));
    }

    @Test
    void shouldRefuseTheKindsWhoseStoresDoNotExistYet() {
        UUID id = UUID.randomUUID();
        assertThat(port.assessment(1, 2, AssessmentPort.AssessmentKind.PRIVATE_CHECK_OUTCOME, id))
                .isEmpty();
        assertThat(port.assessment(1, 2, AssessmentPort.AssessmentKind.SELF_REPORT, id))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"PARTIAL, EVIDENCE_TOO_LARGE", "FAILED, PREPARATION_FAILED"})
    void shouldReportCouldNotCheckWhenCaptureFailedAndNoPracticeRan(ReviewStatus status, ReviewStopReason reason) {
        var review = UUID.randomUUID();
        var pair = new AssessmentPort.ReviewedItem(review, 41);
        var asked = new ReviewAssignmentQuery.ReviewedItem(review, 41);
        when(assignments.recordedForDeveloper(1, 2, Set.of(asked)))
                .thenReturn(Map.of(
                        asked,
                        new ReviewAssignmentQuery.Recorded(
                                status, reason, new ReviewAssignmentQuery.AssignmentCounts(1, 0, 0, 1, 0))));

        assertThat(port.reviewChecks(1, 2, List.of(pair)))
                .hasEntrySatisfying(
                        pair,
                        check -> assertThat(check.checkStatus()).isEqualTo(AssessmentPort.CheckStatus.COULD_NOT_CHECK));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reviewsThatCannotShowCouldNotCheck")
    void shouldOmitCouldNotCheckWithoutAFinishedCaptureFailure(
            String scenario, ReviewStatus status, ReviewStopReason reason, @Nullable AssignmentCounts counts) {
        var review = UUID.randomUUID();
        var pair = new AssessmentPort.ReviewedItem(review, 41);
        var asked = new ReviewAssignmentQuery.ReviewedItem(review, 41);
        when(assignments.recordedForDeveloper(1, 2, Set.of(asked)))
                .thenReturn(Map.of(asked, new ReviewAssignmentQuery.Recorded(status, reason, counts)));

        assertThat(port.reviewChecks(1, 2, List.of(pair)))
                .hasEntrySatisfying(
                        pair,
                        check -> assertThat(check.checkStatus()).as(scenario).isNull());
    }

    private static List<Arguments> reviewsThatCannotShowCouldNotCheck() {
        var failure = ReviewStopReason.PREPARATION_FAILED;
        var finished = ReviewStatus.FAILED;
        var withheld = new AssignmentCounts(1, 0, 0, 1, 0);
        return List.of(
                Arguments.of("one practice was judged", finished, failure, new AssignmentCounts(2, 1, 0, 1, 0)),
                Arguments.of("one outcome was reused", finished, failure, new AssignmentCounts(2, 0, 1, 1, 0)),
                Arguments.of("one assignment is pending", finished, failure, new AssignmentCounts(2, 0, 0, 1, 1)),
                Arguments.of("the counts are unavailable", finished, failure, null),
                Arguments.of("the review can still run", ReviewStatus.RUNNING, failure, withheld),
                Arguments.of(
                        "the practice was held", ReviewStatus.PARTIAL, ReviewStopReason.READINESS_REFUSED, withheld));
    }

    @ParameterizedTest
    @CsvSource({"1, 3, REQUEST", "3, 2, REQUEST", "1, 2, TRIAL"})
    void shouldOmitAProblemFromAForeignOrTrialObservation(long workspaceId, long ownerId, ReviewOrigin origin) {
        var result = mock(ResultHistoryEntry.class);
        var problemId = UUID.randomUUID();
        when(history.findOfProblem(1, problemId, 2, SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .thenReturn(Optional.of(result));
        when(result.copyAuthorityComplete()).thenReturn(true);
        when(result.developerAccountId()).thenReturn(ownerId);
        if (ownerId == 2)
            when(result.item())
                    .thenReturn(new ItemRef(workspaceId, 41, new ItemKind("scm.pull_request"), 421L, null, null));
        if (workspaceId == 1 && ownerId == 2) when(result.origin()).thenReturn(origin);

        assertThat(port.observationOfProblem(1, 2, problemId)).isEmpty();
    }
}
