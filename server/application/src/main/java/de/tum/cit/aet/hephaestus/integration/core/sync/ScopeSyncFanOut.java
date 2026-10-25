package de.tum.cit.aet.hephaestus.integration.core.sync;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the workspace syncs of the scheduled syncs in parallel, at most {@value #CONCURRENT_SCOPES} at a time in this
 * process, and returns only after every workspace sync it started has returned.
 *
 * <p>A workspace sync can hold two database connections: a provider read inside a transaction, and a
 * {@code REQUIRES_NEW} write of users or labels. One limit for all scheduled syncs, GitHub and GitLab together, keeps
 * them to at most eight connections of the server's pool, 20 by default.
 *
 * <p>When the scheduled sync returns, its {@code @SchedulerLock} advice releases the lock and the task
 * scheduler counts the job as ended. Neither may happen while a started workspace sync still writes:
 *
 * <ul>
 *   <li>An interrupt does not end the wait. {@link CompletableFuture#join()} keeps waiting and restores
 *       the interrupt flag. Shutdown stops each workspace sync cooperatively through
 *       {@link SyncJobService}. An interrupt on a virtual thread in a JDBC read would close the
 *       connection that the workspace sync needs to record its outcome.
 *   <li>If the executor rejects a workspace sync, the ones it already accepted are awaited before the
 *       rejection propagates.
 *   <li>Once shutdown has begun, or an interrupt arrives while it waits for a free slot, it starts no
 *       further workspace sync. It awaits the started ones; an interrupt stays set.
 * </ul>
 */
public final class ScopeSyncFanOut {

    private static final Logger log = LoggerFactory.getLogger(ScopeSyncFanOut.class);

    static final int CONCURRENT_SCOPES = 4;

    private static final Semaphore SLOTS = new Semaphore(CONCURRENT_SCOPES);

    private ScopeSyncFanOut() {}

    public record Outcome(int succeeded, int failed, int notStarted) {}

    /**
     * @param starting whether a further workspace sync may start; false once shutdown has begun
     *     ({@link SyncJobService#isRunning()})
     */
    public static Outcome run(
            List<SyncSession> sessions, Consumer<SyncSession> sync, Executor executor, BooleanSupplier starting) {
        List<CompletableFuture<Void>> started = new ArrayList<>(sessions.size());
        boolean interrupted = false;
        try {
            for (SyncSession session : sessions) {
                try {
                    SLOTS.acquire();
                } catch (InterruptedException e) {
                    interrupted = true;
                    break;
                }
                if (!starting.getAsBoolean()) {
                    SLOTS.release();
                    break;
                }
                CompletableFuture<Void> workspaceSync;
                try {
                    workspaceSync = CompletableFuture.runAsync(() -> sync.accept(session), executor);
                } catch (RuntimeException e) {
                    SLOTS.release();
                    throw e;
                }
                started.add(workspaceSync.whenComplete((ignored, error) -> {
                    SLOTS.release();
                    if (error != null) {
                        log.error("Scheduled workspace sync failed: scopeId={}", session.scopeId(), error);
                    }
                }));
            }
        } finally {
            CompletableFuture.allOf(started.toArray(new CompletableFuture<?>[0]))
                    .exceptionally(error -> null)
                    .join();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        int failed = (int) started.stream()
                .filter(CompletableFuture::isCompletedExceptionally)
                .count();
        return new Outcome(started.size() - failed, failed, sessions.size() - started.size());
    }
}
