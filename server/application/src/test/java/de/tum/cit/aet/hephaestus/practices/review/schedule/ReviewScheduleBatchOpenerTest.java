package de.tum.cit.aet.hephaestus.practices.review.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBatchOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCadence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewScheduleBatchOpenerTest extends BaseUnitTest {
    final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    final ReviewScheduleRepository schedules = mock(ReviewScheduleRepository.class);
    final ReviewBatchRepository batches = mock(ReviewBatchRepository.class);
    final ReviewBatchService batchService = mock(ReviewBatchService.class);
    final ReviewBatchScopeQuery scope = mock(ReviewBatchScopeQuery.class);
    final ReviewBatchCostEstimator costs = mock(ReviewBatchCostEstimator.class);
    final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    final ReviewScheduleBatchOpener opener = new ReviewScheduleBatchOpener(
            schedules,
            batches,
            batchService,
            scope,
            costs,
            new ReviewBatchProperties(2, Duration.ofDays(400), 100, Duration.ofDays(90)),
            workspaces,
            mock(ReviewRepository.class));
    ReviewSchedule schedule;

    @BeforeEach
    void setUp() {
        schedule = ReviewSchedule.builder()
                .id(UUID.randomUUID())
                .workspaceId(1)
                .kind("scm.pull_request")
                .cadence(ReviewCadence.DAILY)
                .lookbackDays(1)
                .nextRunAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        when(schedules.findById(schedule.getId())).thenReturn(Optional.of(schedule));
        var ws = new Workspace();
        ws.setId(1L);
        ws.resume();
        lenient().when(workspaces.findById(1L)).thenReturn(Optional.of(ws));
    }

    @Test
    void disabledScheduleDoesNotOpen() {
        schedule.setEnabled(false);
        assertThat(opener.openDueRun(schedule.getId(), now)).isEqualTo(ReviewScheduleOutcome.SKIPPED_DISABLED);
        verifyNoInteractions(batchService);
    }

    @Test
    void unavailableWorkspaceDoesNotOpen() {
        when(workspaces.findById(1L)).thenReturn(Optional.empty());
        assertThat(opener.openDueRun(schedule.getId(), now))
                .isEqualTo(ReviewScheduleOutcome.SKIPPED_WORKSPACE_UNAVAILABLE);
        assertThat(schedule.getNextRunAt()).isEqualTo(now.plus(Duration.ofDays(1)));
        assertThat(schedule.getLastRunAt()).isEqualTo(now);
    }

    @Test
    void activeBatchDefersTheNextSchedule() {
        when(batches.existsByWorkspaceIdAndStatusIn(eq(1L), any())).thenReturn(true);
        assertThat(opener.openDueRun(schedule.getId(), now)).isEqualTo(ReviewScheduleOutcome.SKIPPED_BATCH_UNDER_WAY);
        assertThat(schedule.getNextRunAt()).isEqualTo(now.plus(Duration.ofDays(1)));
    }

    @Test
    void emptyScopeAdvancesCadenceWithoutCreatingABatch() {
        when(scope.select(1, "scm.pull_request", now.minus(Duration.ofDays(1)), now, 101))
                .thenReturn(List.of());
        assertThat(opener.openDueRun(schedule.getId(), now)).isEqualTo(ReviewScheduleOutcome.SKIPPED_EMPTY_SCOPE);
        assertThat(schedule.getLastRunAt()).isEqualTo(now);
        verify(batches, never()).saveAndFlush(any());
    }

    @Test
    void selectedTargetsAreFrozenWithScheduleOrigin() {
        when(scope.select(1, "scm.pull_request", now.minus(Duration.ofDays(1)), now, 101))
                .thenReturn(List.of(4L, 8L));
        assertThat(opener.openDueRun(schedule.getId(), now)).isEqualTo(ReviewScheduleOutcome.OPENED);
        var captured = ArgumentCaptor.forClass(ReviewBatch.class);
        verify(batches).saveAndFlush(captured.capture());
        assertThat(captured.getValue().getOrigin()).isEqualTo(ReviewBatchOrigin.SCHEDULE);
        assertThat(captured.getValue().getScheduleId()).isEqualTo(schedule.getId());
        verify(batchService).freezeTargets(captured.getValue(), List.of(4L, 8L));
    }
}
