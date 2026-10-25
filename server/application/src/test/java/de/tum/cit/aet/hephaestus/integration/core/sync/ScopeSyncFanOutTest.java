package de.tum.cit.aet.hephaestus.integration.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScopeSyncFanOutTest extends BaseUnitTest {

    private final ExecutorService workspaceSyncs = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void releaseWork() {
        release.countDown();
        workspaceSyncs.close();
    }

    @Test
    void shouldKeepWaitingAndKeepTheInterruptWhenTheScheduledSyncIsInterrupted() throws Exception {
        AtomicReference<ScopeSyncFanOut.@Nullable Outcome> outcome = new AtomicReference<>();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        Thread run = Thread.ofPlatform().start(() -> {
            outcome.set(ScopeSyncFanOut.run(List.of(session(1)), session -> heldSync(), workspaceSyncs, () -> true));
            interruptedOnReturn.set(Thread.currentThread().isInterrupted());
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        run.interrupt();
        // Either the run returned, or it consumed the interrupt and waits again.
        await().atMost(Duration.ofSeconds(10))
                .until(() -> !run.isAlive() || (run.getState() == Thread.State.WAITING && !run.isInterrupted()));

        assertThat(run.isAlive())
                .as("the started workspace sync is still running")
                .isTrue();

        release.countDown();

        assertThat(run.join(Duration.ofSeconds(10))).isTrue();
        assertThat(outcome.get()).isEqualTo(new ScopeSyncFanOut.Outcome(1, 0, 0));
        assertThat(interruptedOnReturn).isTrue();
    }

    @Test
    void shouldAwaitTheAcceptedWorkspaceSyncBeforeTheRejectionPropagates() throws Exception {
        AtomicInteger submissions = new AtomicInteger();
        Executor acceptsOne = task -> {
            if (submissions.getAndIncrement() == 0) {
                workspaceSyncs.execute(task);
                return;
            }
            throw new RejectedExecutionException("The executor is closed");
        };
        AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
        Thread run = Thread.ofPlatform().start(() -> {
            try {
                ScopeSyncFanOut.run(List.of(session(1), session(2)), session -> heldSync(), acceptsOne, () -> true);
            } catch (RejectedExecutionException e) {
                thrown.set(e);
            }
        });
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10)).until(() -> submissions.get() == 2);
        await().atMost(Duration.ofSeconds(10)).until(() -> !run.isAlive() || run.getState() == Thread.State.WAITING);

        assertThat(run.isAlive())
                .as("the rejection waits for the accepted workspace sync")
                .isTrue();
        assertThat(thrown.get()).isNull();

        release.countDown();

        assertThat(run.join(Duration.ofSeconds(10))).isTrue();
        assertThat(thrown.get()).isInstanceOf(RejectedExecutionException.class);
    }

    /** More workspaces than connections: each workspace sync holds a connection until it is released. */
    @Test
    void shouldRunAtMostFourWorkspaceSyncsAtOnceSoAPoolOfFiveIsEnoughForThirty() throws Exception {
        Semaphore pool = new Semaphore(5);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        CountDownLatch slotsTaken = new CountDownLatch(ScopeSyncFanOut.CONCURRENT_SCOPES);
        AtomicReference<ScopeSyncFanOut.@Nullable Outcome> outcome = new AtomicReference<>();
        Thread run = Thread.ofPlatform()
                .start(() -> outcome.set(ScopeSyncFanOut.run(
                        sessions(30),
                        session -> {
                            if (!pool.tryAcquire()) {
                                throw new IllegalStateException("Connection is not available");
                            }
                            try {
                                mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                                slotsTaken.countDown();
                                awaitRelease();
                            } finally {
                                running.decrementAndGet();
                                pool.release();
                            }
                        },
                        workspaceSyncs,
                        () -> true)));
        assertThat(slotsTaken.await(10, TimeUnit.SECONDS)).isTrue();
        awaitWaitingForASlot(run);

        assertThat(running).hasValue(ScopeSyncFanOut.CONCURRENT_SCOPES);

        release.countDown();
        assertThat(run.join(Duration.ofSeconds(10))).isTrue();
        assertThat(outcome.get()).isEqualTo(new ScopeSyncFanOut.Outcome(30, 0, 0));
        assertThat(mostAtOnce).hasValueLessThanOrEqualTo(ScopeSyncFanOut.CONCURRENT_SCOPES);
    }

    /** The GitHub and GitLab syncs share one cron, and one limit. */
    @Test
    void shouldShareTheLimitBetweenTwoScheduledSyncs() throws Exception {
        AtomicInteger running = new AtomicInteger();
        CountDownLatch slotsTaken = new CountDownLatch(ScopeSyncFanOut.CONCURRENT_SCOPES);
        Runnable scheduledSync = () -> ScopeSyncFanOut.run(
                sessions(ScopeSyncFanOut.CONCURRENT_SCOPES),
                session -> {
                    running.incrementAndGet();
                    slotsTaken.countDown();
                    awaitRelease();
                },
                workspaceSyncs,
                () -> true);
        Thread github = Thread.ofPlatform().start(scheduledSync);
        Thread gitlab = Thread.ofPlatform().start(scheduledSync);
        assertThat(slotsTaken.await(10, TimeUnit.SECONDS)).isTrue();
        await().atMost(Duration.ofSeconds(10))
                .until(() -> github.getState() == Thread.State.WAITING && gitlab.getState() == Thread.State.WAITING);

        assertThat(running).hasValue(ScopeSyncFanOut.CONCURRENT_SCOPES);

        release.countDown();
        assertThat(github.join(Duration.ofSeconds(10))).isTrue();
        assertThat(gitlab.join(Duration.ofSeconds(10))).isTrue();
        assertThat(running).hasValue(2 * ScopeSyncFanOut.CONCURRENT_SCOPES);
    }

    @Test
    void shouldStartNoFurtherWorkspaceSyncWhenInterruptedWhileWaitingForASlot() throws Exception {
        CountDownLatch slotsTaken = new CountDownLatch(ScopeSyncFanOut.CONCURRENT_SCOPES);
        AtomicReference<ScopeSyncFanOut.@Nullable Outcome> outcome = new AtomicReference<>();
        AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        Thread run = Thread.ofPlatform().start(() -> {
            outcome.set(ScopeSyncFanOut.run(
                    sessions(ScopeSyncFanOut.CONCURRENT_SCOPES + 2),
                    session -> {
                        slotsTaken.countDown();
                        awaitRelease();
                    },
                    workspaceSyncs,
                    () -> true));
            interruptedOnReturn.set(Thread.currentThread().isInterrupted());
        });
        assertThat(slotsTaken.await(10, TimeUnit.SECONDS)).isTrue();
        awaitWaitingForASlot(run);

        run.interrupt();
        release.countDown();

        assertThat(run.join(Duration.ofSeconds(10))).isTrue();
        assertThat(outcome.get()).isEqualTo(new ScopeSyncFanOut.Outcome(ScopeSyncFanOut.CONCURRENT_SCOPES, 0, 2));
        assertThat(interruptedOnReturn).isTrue();
    }

    @Test
    void shouldStartNoFurtherWorkspaceSyncOnceShutdownHasBegun() throws Exception {
        CountDownLatch slotsTaken = new CountDownLatch(ScopeSyncFanOut.CONCURRENT_SCOPES);
        AtomicBoolean starting = new AtomicBoolean(true);
        AtomicReference<ScopeSyncFanOut.@Nullable Outcome> outcome = new AtomicReference<>();
        Thread run = Thread.ofPlatform()
                .start(() -> outcome.set(ScopeSyncFanOut.run(
                        sessions(ScopeSyncFanOut.CONCURRENT_SCOPES + 2),
                        session -> {
                            slotsTaken.countDown();
                            awaitRelease();
                        },
                        workspaceSyncs,
                        starting::get)));
        assertThat(slotsTaken.await(10, TimeUnit.SECONDS)).isTrue();
        awaitWaitingForASlot(run);

        starting.set(false);
        release.countDown();

        assertThat(run.join(Duration.ofSeconds(10))).isTrue();
        assertThat(outcome.get()).isEqualTo(new ScopeSyncFanOut.Outcome(ScopeSyncFanOut.CONCURRENT_SCOPES, 0, 2));
    }

    /** All slots are taken, so the next workspace sync waits in the semaphore. */
    private static void awaitWaitingForASlot(Thread run) {
        await().atMost(Duration.ofSeconds(10)).until(() -> run.getState() == Thread.State.WAITING);
    }

    private void awaitRelease() {
        try {
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<SyncSession> sessions(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(ScopeSyncFanOutTest::session)
                .toList();
    }

    private void heldSync() {
        started.countDown();
        try {
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static SyncSession session(long scopeId) {
        return new SyncSession(
                scopeId,
                "workspace-" + scopeId,
                "Workspace " + scopeId,
                "group",
                null,
                null,
                List.of(),
                new SyncContextProvider.SyncContext(scopeId, "workspace-" + scopeId, "Workspace " + scopeId, null));
    }
}
