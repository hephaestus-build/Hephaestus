package de.tum.cit.aet.hephaestus.leaderboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.leaderboard.tasks.LeaguePointsUpdateTask;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceCreatedEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * Pins the scheduler's concurrency-critical behavior, which is invisible from the higher-level
 * task tests: only the replica that wins the per-workspace ShedLock runs (and it always unlocks),
 * a workspace deleted since registration self-cancels its trigger, and one notification channel
 * failing does not stop the league-points update or sibling channels.
 */
class LeaderboardTaskSchedulerTest extends BaseUnitTest {

    @Mock
    private LeaderboardScheduleResolver scheduleResolver;

    @Mock
    private TaskScheduler taskScheduler;

    @Mock
    private LeaguePointsUpdateTask leaguePointsUpdateTask;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private LockProvider lockProvider;

    @Mock
    private SimpleLock lock;

    @Mock
    private LeaderboardNotificationTask notificationTask;

    private @Nullable LeaderboardProperties properties;

    @BeforeEach
    void setUp() {
        when(scheduleResolver.cron(any(Workspace.class))).thenReturn("0 0 9 ? * 1");
    }

    /** Build the scheduler, register one workspace, and return the Runnable handed to the scheduler. */
    private Runnable registerAndCaptureTick(boolean notificationsEnabled, List<LeaderboardNotificationTask> tasks) {
        properties = new LeaderboardProperties(
                new LeaderboardProperties.Schedule(1, "09:00"),
                new LeaderboardProperties.Notification(notificationsEnabled));
        LeaderboardTaskScheduler scheduler = new LeaderboardTaskScheduler(
                properties,
                scheduleResolver,
                taskScheduler,
                tasks,
                leaguePointsUpdateTask,
                workspaceRepository,
                lockProvider);
        org.mockito.Mockito.doReturn(Mockito.mock(ScheduledFuture.class))
                .when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));
        when(workspaceRepository.findById(7L)).thenReturn(Optional.of(workspace(7L)));

        scheduler.onWorkspaceCreated(new WorkspaceCreatedEvent(7L, IntegrationKind.GITHUB));

        ArgumentCaptor<Runnable> tick = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(tick.capture(), any(Trigger.class));
        return tick.getValue();
    }

    @Test
    void lockHeldByAnotherReplica_skipsRunAndDoesNotUnlock() {
        Runnable tick = registerAndCaptureTick(false, List.of());
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        tick.run();

        verify(leaguePointsUpdateTask, never()).runForWorkspace(any());
        verify(lock, never()).unlock();
    }

    @Test
    void lockAcquired_runsLeagueUpdateAndAlwaysUnlocks() {
        Runnable tick = registerAndCaptureTick(false, List.of());
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        tick.run();

        verify(leaguePointsUpdateTask).runForWorkspace(any(Workspace.class));
        verify(lock).unlock();
    }

    @Test
    void workspaceDeletedSinceRegistration_selfCancelsWithoutRunning() {
        Runnable tick = registerAndCaptureTick(false, List.of());
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        when(workspaceRepository.findById(7L)).thenReturn(Optional.empty());

        tick.run();

        verify(leaguePointsUpdateTask, never()).runForWorkspace(any());
        verify(lock).unlock();
    }

    @Test
    void notificationChannelThrows_leagueUpdateStillRuns() {
        Runnable tick = registerAndCaptureTick(true, List.of(notificationTask));
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        Mockito.doThrow(new IllegalStateException("slack down"))
                .when(notificationTask)
                .runForWorkspace(any(Workspace.class));

        tick.run();

        verify(notificationTask).runForWorkspace(any(Workspace.class));
        verify(leaguePointsUpdateTask).runForWorkspace(any(Workspace.class));
        verify(lock).unlock();
    }

    private static Workspace workspace(long id) {
        Workspace w = new Workspace();
        w.setId(id);
        return w;
    }
}
