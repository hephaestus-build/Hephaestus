package de.tum.cit.aet.hephaestus.agent.backfill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

/**
 * One due schedule's turn.
 *
 * <p>What matters here is what is <em>not</em> created: a sweep that opened a campaign on every tick
 * regardless would stack a paused run per night against a suspended workspace and price scopes nobody
 * can review.
 */
@DisplayName("Review sweep campaign opener")
class ReviewSweepCampaignOpenerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 4L;
    private static final long ACCOUNT_ID = 91L;
    private static final UUID SCHEDULE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-08-09T02:00:00Z");

    @Mock
    private ReviewSweepScheduleRepository scheduleRepository;

    @Mock
    private ReviewBackfillRunRepository runRepository;

    @Mock
    private ReviewBackfillScopeRepository scopeRepository;

    @Mock
    private ReviewBackfillCostEstimator costEstimator;

    @Mock
    private ConfigAuditPort configAudit;

    private final ReviewBackfillProperties properties =
            new ReviewBackfillProperties(25, Duration.ofDays(400), 5000, Duration.ofDays(90));

    private ReviewSweepCampaignOpener opener() {
        return new ReviewSweepCampaignOpener(
                scheduleRepository, runRepository, scopeRepository, costEstimator, properties, configAudit);
    }

    /** Directly RUNNING, not queued for confirmation: creating the schedule already was the confirmation. */
    @Test
    void aDueScheduleOpensARunningCampaignStampedAsASweep() {
        ReviewSweepSchedule schedule = schedule();
        givenSchedule(schedule);
        when(scopeRepository.countPullRequests(eq(WORKSPACE_ID), any(), any())).thenReturn(4L);
        when(costEstimator.estimateTotalUsd(eq(WORKSPACE_ID), any(), anyInt())).thenReturn(new BigDecimal("1.20"));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.OPENED);

        ArgumentCaptor<ReviewBackfillRun> run = ArgumentCaptor.forClass(ReviewBackfillRun.class);
        verify(runRepository).save(run.capture());
        assertThat(run.getValue().getStatus()).isEqualTo(ReviewBackfillStatus.RUNNING);
        assertThat(run.getValue().getDiscoveredVia()).isEqualTo(DiscoveredVia.SWEEP);
        assertThat(run.getValue().getSweepScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(run.getValue().getConfirmedByAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(run.getValue().getFromAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(run.getValue().getToAt()).isEqualTo(NOW);
        verify(configAudit).record(any(ConfigAuditEntry.class));
    }

    @Test
    void openingACampaignRecordsItAndMovesToTheNextOccurrence() {
        ReviewSweepSchedule schedule = schedule();
        givenSchedule(schedule);
        when(scopeRepository.countPullRequests(eq(WORKSPACE_ID), any(), any())).thenReturn(1L);
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        opener().openDueRun(SCHEDULE_ID, NOW);

        assertThat(schedule.getLastRunAt()).isEqualTo(NOW);
        assertThat(schedule.getNextRunAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        verify(scheduleRepository).save(schedule);
    }

    /**
     * {@code lastRunAt} is the only signal telling a working schedule from one skipped for a month; a
     * no-op tick must leave it alone or that distinction vanishes from the admin's screen.
     */
    @Test
    void aTickThatOpenedNothingDoesNotClaimToHaveRun() {
        ReviewSweepSchedule schedule = schedule();
        givenSchedule(schedule);
        when(scopeRepository.countPullRequests(eq(WORKSPACE_ID), any(), any())).thenReturn(0L);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_EMPTY_SCOPE);

        assertThat(schedule.getLastRunAt()).isNull();
        assertThat(schedule.getNextRunAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        verify(runRepository, never()).save(any());
    }

    /**
     * One campaign per workspace at a time: two overlapping runs would each read the other's ledger rows
     * as already covered, so neither would cover its own scope.
     */
    @Test
    void aWorkspaceStillWorkingThroughACampaignDoesNotGetASecond() {
        givenSchedule(schedule());
        when(runRepository.existsByWorkspaceIdAndStatusIn(eq(WORKSPACE_ID), any()))
                .thenReturn(true);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_CAMPAIGN_UNDER_WAY);

        verify(runRepository, never()).save(any());
        verifyNoInteractions(scopeRepository, costEstimator, configAudit);
    }

    @Test
    void aSuspendedWorkspaceDoesNotAccumulateAPausedCampaignPerNight() {
        ReviewSweepSchedule schedule = schedule();
        schedule.getWorkspace().setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        givenSchedule(schedule);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_WORKSPACE_UNAVAILABLE);

        verify(runRepository, never()).save(any());
    }

    @Test
    void aWorkspaceWithPracticesSwitchedOffIsNotSwept() {
        ReviewSweepSchedule schedule = schedule();
        schedule.getWorkspace().getFeatures().setPracticesEnabled(false);
        givenSchedule(schedule);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_WORKSPACE_UNAVAILABLE);

        verify(runRepository, never()).save(any());
    }

    /**
     * A workspace producing more work in two days than a whole hand-confirmed campaign may cover has a
     * broken mirror; a nightly sweep is the wrong place to spend a month's budget finding that out.
     */
    @Test
    void anImplausibleScopeIsRefusedRatherThanPaidFor() {
        givenSchedule(schedule());
        when(scopeRepository.countPullRequests(eq(WORKSPACE_ID), any(), any())).thenReturn(9_000L);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_SCOPE_TOO_LARGE);

        verify(runRepository, never()).save(any());
        verifyNoInteractions(costEstimator);
    }

    @Test
    void aScheduleSwitchedOffBetweenSelectionAndActionOpensNothing() {
        ReviewSweepSchedule schedule = schedule();
        schedule.setEnabled(false);
        givenSchedule(schedule);

        assertThat(opener().openDueRun(SCHEDULE_ID, NOW)).isEqualTo(ReviewSweepOutcome.SKIPPED_DISABLED);

        verify(runRepository, never()).save(any());
    }

    /**
     * The deferral exists so a deterministically failing sweep does not come due again immediately,
     * failing the same way at the same cost each tick.
     */
    @Test
    void aFailedTurnIsDeferredToTheNextOccurrenceWithoutOpeningAnything() {
        ReviewSweepSchedule schedule = schedule();
        when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(schedule));

        opener().deferAfterFailure(SCHEDULE_ID, NOW);

        assertThat(schedule.getNextRunAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(schedule.getLastRunAt()).isNull();
        verify(runRepository, never()).save(any());
    }

    private void givenSchedule(ReviewSweepSchedule schedule) {
        when(scheduleRepository.findByIdWithWorkspace(SCHEDULE_ID)).thenReturn(Optional.of(schedule));
        when(scheduleRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ReviewSweepSchedule schedule() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.getFeatures().setPracticesEnabled(true);

        ReviewSweepSchedule schedule = new ReviewSweepSchedule();
        schedule.setId(SCHEDULE_ID);
        schedule.setWorkspace(workspace);
        schedule.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        schedule.setCadence(ReviewSweepCadence.DAILY);
        schedule.setLookbackDays(2);
        schedule.setEnabled(true);
        schedule.setNextRunAt(NOW);
        schedule.setCreatedByAccountId(ACCOUNT_ID);
        return schedule;
    }
}
