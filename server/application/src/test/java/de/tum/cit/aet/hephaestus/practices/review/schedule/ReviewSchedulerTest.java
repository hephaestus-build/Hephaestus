package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

@DisplayName("Review sweep scheduler")
class ReviewSchedulerTest extends BaseUnitTest {

    private static final UUID FIRST = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Mock
    private ReviewScheduleRepository scheduleRepository;

    @Mock
    private ReviewScheduleBatchOpener opener;

    private ReviewScheduler scheduler() {
        return new ReviewScheduler(scheduleRepository, opener);
    }

    @Test
    void oneWorkspacesFailedSweepDoesNotStopTheNext() {
        when(scheduleRepository.findDue(any(), any())).thenReturn(List.of(schedule(FIRST), schedule(SECOND)));
        when(opener.openDueRun(eq(FIRST), any())).thenThrow(new IllegalStateException("scope query blew up"));
        when(opener.openDueRun(eq(SECOND), any())).thenReturn(ReviewScheduleOutcome.OPENED);

        scheduler().tick();

        verify(opener).openDueRun(eq(SECOND), any());
    }

    @Test
    void aSweepThatThrewIsPushedToItsNextOccurrenceInsteadOfRetryingEveryFiveMinutes() {
        when(scheduleRepository.findDue(any(), any())).thenReturn(List.of(schedule(FIRST)));
        when(opener.openDueRun(eq(FIRST), any())).thenThrow(new IllegalStateException("scope query blew up"));

        scheduler().tick();

        verify(opener).deferAfterFailure(eq(FIRST), any(Instant.class));
    }

    @Test
    void aFailedDeferralIsSwallowedSoTheRemainingSchedulesStillRun() {
        when(scheduleRepository.findDue(any(), any())).thenReturn(List.of(schedule(FIRST), schedule(SECOND)));
        when(opener.openDueRun(eq(FIRST), any())).thenThrow(new IllegalStateException("scope query blew up"));
        doThrow(new IllegalStateException("and so did the deferral"))
                .when(opener)
                .deferAfterFailure(eq(FIRST), any());
        when(opener.openDueRun(eq(SECOND), any())).thenReturn(ReviewScheduleOutcome.OPENED);

        scheduler().tick();

        verify(opener).openDueRun(eq(SECOND), any());
    }

    @Test
    void aBacklogOfDueSchedulesIsReadPageAfterPageInOneTick() {
        var firstPage = IntStream.range(0, 20)
                .mapToObj(i -> schedule(UUID.randomUUID()))
                .toList();
        var secondPage = IntStream.range(0, 20)
                .mapToObj(i -> schedule(UUID.randomUUID()))
                .toList();
        when(scheduleRepository.findDue(any(), any()))
                .thenReturn(firstPage)
                .thenReturn(secondPage)
                .thenReturn(List.of());
        when(opener.openDueRun(any(), any())).thenReturn(ReviewScheduleOutcome.OPENED);

        scheduler().tick();

        verify(opener, times(40)).openDueRun(any(), any());
    }

    @Test
    void aScheduleThatStaysDueIsTriedOnceInATick() {
        when(scheduleRepository.findDue(any(), any())).thenReturn(List.of(schedule(FIRST)));
        when(opener.openDueRun(eq(FIRST), any())).thenThrow(new IllegalStateException("scope query blew up"));
        doThrow(new IllegalStateException("and so did the deferral"))
                .when(opener)
                .deferAfterFailure(eq(FIRST), any());

        scheduler().tick();

        verify(opener, times(1)).openDueRun(eq(FIRST), any());
    }

    private static ReviewSchedule schedule(UUID id) {
        ReviewSchedule schedule = new ReviewSchedule();
        schedule.setId(id);
        return schedule;
    }
}
