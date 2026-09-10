package de.tum.cit.aet.hephaestus.leaderboard;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.leaderboard.tasks.LeaguePointsUpdateTask;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceCreatedEvent;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceScheduleChangedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Schedules the weekly leaderboard work <b>per workspace</b>: each workspace runs its notification
 * channels + league-points recompute on its own cron, derived from
 * {@link LeaderboardScheduleResolver} (the workspace's {@code leaderboardScheduleDay}/{@code Time}
 * override, falling back to the global {@link LeaderboardProperties#schedule()} default).
 *
 * <p>Registration: one {@link CronTrigger} per workspace, (re)built at startup, when a workspace is
 * created ({@link WorkspaceCreatedEvent}), and when its schedule changes
 * ({@link WorkspaceScheduleChangedEvent}). A workspace deleted between ticks self-cancels on its
 * next fire (the runnable re-loads by id and cancels its own future when the row is gone), so no
 * delete event is required.
 *
 * <p>Notification channels register as Spring beans implementing {@link LeaderboardNotificationTask}
 * — the scheduler stays vendor-agnostic; the Slack task lives in the slack module. The global
 * {@code notification.enabled} flag is the kill-switch for notification channels (league-points
 * recompute always runs). ShedLock keys per {@code (workspace, task)} so only one replica runs each
 * on a given tick.
 *
 * <p>In-process registry caveat: cron triggers live on this replica's {@link TaskScheduler}. Every
 * replica registers the same triggers and races for the ShedLock, so exactly one runs each tick —
 * but a schedule edit only re-registers on the replica that handles the HTTP request. The next
 * occurrence still fires correctly on all replicas because {@link #onScheduleChanged} is driven by
 * an {@link org.springframework.context.ApplicationEvent}, which in a single-process deployment
 * reaches the one scheduler; multi-replica live edits converge on the next app restart. (The data
 * is always authoritative — only the in-memory trigger cadence can lag a peer until restart.)
 */
@Order(value = Ordered.LOWEST_PRECEDENCE)
@Component
// Build profiles have neither workspace data to schedule nor a server TaskScheduler.
@Profile("!test & !specs & !cds-training")
@ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class LeaderboardTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardTaskScheduler.class);

    // Cron fires on every server replica; ShedLock ensures only one replica runs each task per
    // tick (lockAtLeastFor covers clock skew so a fast task can't release before peers fire).
    //
    // lockAtMostFor is a safety ceiling, not an expected runtime: the weekly work is O(members)
    // and completes in seconds-to-minutes even for large workspaces, so 1h is comfortably above
    // p99. It bounds how long a crashed replica's lock survives before another may retry — and
    // because the league-points update is idempotent per cycle (it records the processed cycle
    // and no-ops a repeat), a retry after lock expiry cannot double-award.
    private static final Duration LOCK_AT_MOST_FOR = Duration.ofHours(1);
    private static final Duration LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);

    private final LeaderboardProperties leaderboardProperties;
    private final LeaderboardScheduleResolver scheduleResolver;
    private final TaskScheduler taskScheduler;
    private final List<LeaderboardNotificationTask> notificationTasks;
    private final LeaguePointsUpdateTask leaguePointsUpdateTask;
    private final WorkspaceRepository workspaceRepository;
    private final LockProvider lockProvider;

    /** Live cron registration per workspace id, so schedule edits can cancel + re-register. */
    private final Map<Long, ScheduledFuture<?>> registrations = new ConcurrentHashMap<>();

    public LeaderboardTaskScheduler(
            LeaderboardProperties leaderboardProperties,
            LeaderboardScheduleResolver scheduleResolver,
            TaskScheduler taskScheduler,
            List<LeaderboardNotificationTask> notificationTasks,
            LeaguePointsUpdateTask leaguePointsUpdateTask,
            WorkspaceRepository workspaceRepository,
            LockProvider lockProvider) {
        this.leaderboardProperties = leaderboardProperties;
        this.scheduleResolver = scheduleResolver;
        this.taskScheduler = taskScheduler;
        this.notificationTasks = notificationTasks;
        this.leaguePointsUpdateTask = leaguePointsUpdateTask;
        this.workspaceRepository = workspaceRepository;
        this.lockProvider = lockProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleAllWorkspaces() {
        List<Workspace> workspaces = workspaceRepository.findAll();
        log.info("Scheduling leaderboard tasks for {} workspace(s)", workspaces.size());
        for (Workspace workspace : workspaces) {
            register(workspace);
        }
    }

    /** A newly-created workspace gets its cron registered immediately, no restart needed. */
    @EventListener
    public void onWorkspaceCreated(WorkspaceCreatedEvent event) {
        workspaceRepository.findById(event.workspaceId()).ifPresent(this::register);
    }

    /**
     * A schedule edit cancels the old trigger and re-registers at the new cadence. Bound to the
     * commit of the editing transaction so the in-memory cron only changes once the new schedule is
     * durable (a rolled-back edit leaves the existing trigger untouched).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(WorkspaceScheduleChangedEvent event) {
        workspaceRepository.findById(event.workspaceId()).ifPresent(this::register);
    }

    /** (Re)register the per-workspace cron, cancelling any prior trigger for the same workspace. */
    private synchronized void register(Workspace workspace) {
        Long workspaceId = workspace.getId();
        if (workspaceId == null) {
            return;
        }
        String cron = scheduleResolver.cron(workspace);
        if (!CronExpression.isValidExpression(cron)) {
            log.error("Rejected invalid cron expression: workspaceId={}, cronExpression={}", workspaceId, cron);
            return;
        }

        cancelRegistration(workspaceId);

        try {
            // Fire in the server's timezone — the same zone LeaderboardScheduleResolver uses for
            // its window math, so cron fire-time and cycle boundary always agree. (All workspaces
            // share the server zone; there is no per-workspace timezone today.)
            ScheduledFuture<?> future = taskScheduler.schedule(
                    locked(() -> runForWorkspace(workspaceId), workspaceId),
                    new CronTrigger(cron, TimeZone.getDefault()));
            if (future != null) {
                registrations.put(workspaceId, future);
            }
            log.info("Scheduled leaderboard tasks: workspaceId={}, cronExpression={}", workspaceId, cron);
        } catch (TaskRejectedException ex) {
            log.warn("Skipped scheduling: reason=taskRejected, workspaceId={}", workspaceId, ex);
        }
    }

    /**
     * Cancel and forget a workspace's trigger. Shares {@code register()}'s monitor so a self-cancel
     * (workspace deleted, fired from a scheduler thread) can't race a concurrent re-registration.
     */
    private synchronized void cancelRegistration(long workspaceId) {
        ScheduledFuture<?> previous = registrations.remove(workspaceId);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    /**
     * Run all leaderboard work for one workspace on its tick. Re-loads the workspace by id so a row
     * deleted since registration self-cancels (and notification-enabled / schedule reflect the
     * latest persisted state). Leagues always run; notification channels run only when the global
     * notification kill-switch is on.
     */
    private void runForWorkspace(long workspaceId) {
        Optional<Workspace> current = workspaceRepository.findById(workspaceId);
        if (current.isEmpty()) {
            log.info("Cancelled leaderboard schedule: reason=workspaceDeleted, workspaceId={}", workspaceId);
            cancelRegistration(workspaceId);
            return;
        }
        Workspace workspace = current.get();

        if (leaderboardProperties.notification().enabled()) {
            for (LeaderboardNotificationTask task : notificationTasks) {
                try {
                    task.runForWorkspace(workspace);
                } catch (RuntimeException e) {
                    log.warn(
                            "Leaderboard notification task failed: task={}, workspaceId={}, error={}",
                            task.getClass().getSimpleName(),
                            workspaceId,
                            e.getMessage());
                }
            }
        }

        try {
            leaguePointsUpdateTask.runForWorkspace(workspace);
        } catch (RuntimeException e) {
            log.error("League points update failed: workspaceId={}", workspaceId, e);
        }
    }

    /**
     * Wraps the per-workspace run so that, across replicas, only the replica that acquires the
     * workspace's ShedLock executes it on a given tick.
     */
    private Runnable locked(Runnable task, long workspaceId) {
        String lockName = "leaderboard-workspace-" + workspaceId;
        return () -> {
            Optional<SimpleLock> lock = lockProvider.lock(
                    new LockConfiguration(Instant.now(), lockName, LOCK_AT_MOST_FOR, LOCK_AT_LEAST_FOR));
            if (lock.isEmpty()) {
                log.info("Skipped leaderboard task: reason=lockHeldByAnotherReplica, lock={}", lockName);
                return;
            }
            try {
                task.run();
            } finally {
                lock.get().unlock();
            }
        };
    }
}
