package de.tum.cit.aet.hephaestus.agent.mentor.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrency invariants for the per-thread turn mutex. The lock map MUST stay bounded as
 * threads come and go (otherwise multi-tenant servers leak memory).
 */
class MentorTurnLockTest extends BaseUnitTest {

    @Test
    @DisplayName("Two parallel callers on the same key — exactly one wins")
    void exactlyOneAcquiresUnderContention() throws Exception {
        MentorTurnLock lock = new MentorTurnLock();
        MentorTurnLock.ThreadKey key = new MentorTurnLock.ThreadKey(1L, UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        try {
            var firstFuture = pool.submit(() -> {
                start.await();
                return lock.withLockOr409(key, () -> {
                    acquired.incrementAndGet();
                    holding.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Boolean.TRUE;
                });
            });
            var secondFuture = pool.submit(() -> {
                start.await();
                holding.await(5, TimeUnit.SECONDS);
                // First caller is holding the lock — we expect tryLock() to fail.
                return lock.withLockOr409(key, () -> {
                    acquired.incrementAndGet();
                    return Boolean.TRUE;
                });
            });
            start.countDown();
            // Wait for second caller to attempt; then release the first.
            // Polling avoids the test ordering ambiguity: second caller must observe failure.
            while (!holding.await(50, TimeUnit.MILLISECONDS)) {}
            Thread.sleep(50); // small grace so second caller has scheduled its tryLock
            Optional<Boolean> secondResult = secondFuture.get(5, TimeUnit.SECONDS);
            assertThat(secondResult).isEmpty(); // 409 conflict
            release.countDown();
            Optional<Boolean> firstResult = firstFuture.get(5, TimeUnit.SECONDS);
            assertThat(firstResult).contains(Boolean.TRUE);
        } finally {
            pool.shutdownNow();
        }
        assertThat(acquired.get()).isEqualTo(1);
        // Both holders gone → map empties.
        assertThat(lock.activeKeys()).isZero();
    }

    @Test
    void independentKeysRunInParallel() throws Exception {
        // Sequential calls cannot prove non-serialisation: a broken global lock would also pass
        // sequentially. Fork two threads on independent keys, hold them simultaneously, and
        // assert their critical sections actually overlap in wall-clock time.
        MentorTurnLock lock = new MentorTurnLock();
        MentorTurnLock.ThreadKey a = new MentorTurnLock.ThreadKey(1L, UUID.randomUUID());
        MentorTurnLock.ThreadKey b = new MentorTurnLock.ThreadKey(1L, UUID.randomUUID());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothInside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        try {
            var fa = pool.submit(() -> lock.withLockOr409(a, () -> {
                long enteredA = System.nanoTime();
                bothInside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return enteredA;
            }));
            var fb = pool.submit(() -> lock.withLockOr409(b, () -> {
                long enteredB = System.nanoTime();
                bothInside.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return enteredB;
            }));
            // Both threads MUST be inside their critical sections at the same time. If the lock
            // were global, only one would arrive; bothInside would never reach zero and this
            // assertion times out.
            assertThat(bothInside.await(5, TimeUnit.SECONDS))
                    .as("both threads inside concurrently")
                    .isTrue();
            release.countDown();
            assertThat(fa.get(5, TimeUnit.SECONDS)).isPresent();
            assertThat(fb.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            pool.shutdownNow();
        }
        assertThat(lock.activeKeys()).isZero(); // entries reaped after release
    }

    @Test
    void exceptionStillReleasesLock() {
        MentorTurnLock lock = new MentorTurnLock();
        MentorTurnLock.ThreadKey key = new MentorTurnLock.ThreadKey(1L, UUID.randomUUID());

        try {
            lock.withLockOr409(key, () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // pass-through
        }
        // After the exception we must be able to re-acquire.
        Optional<String> second = lock.withLockOr409(key, () -> "second");
        assertThat(second).contains("second");
        assertThat(lock.activeKeys()).isZero();
    }

    // Sandbox-level FIFO lock (multi-session)

    // Closing the scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    @Test
    void sandboxLock_sameKeySerialises_differentKeysParallel() throws Exception {
        MentorTurnLock lock = new MentorTurnLock();
        MentorTurnLock.SandboxKey shared = new MentorTurnLock.SandboxKey(1L, 100L);
        MentorTurnLock.SandboxKey isolated = new MentorTurnLock.SandboxKey(1L, 200L);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch firstMayLeave = new CountDownLatch(1);
        AtomicInteger insideShared = new AtomicInteger();
        AtomicInteger maxConcurrentShared = new AtomicInteger();
        try {
            // Two threads compete for the same SandboxKey — must serialise.
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try (var ignored = lock.acquireSandboxLock(shared)) {
                        int now = insideShared.incrementAndGet();
                        maxConcurrentShared.accumulateAndGet(now, Math::max);
                        if (firstInside.getCount() > 0) {
                            firstInside.countDown();
                            firstMayLeave.await(5, TimeUnit.SECONDS);
                        } else {
                            // Second arrival — verified it had to wait for the first to release.
                            assertThat(insideShared.get()).isEqualTo(1);
                        }
                        insideShared.decrementAndGet();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            // A third thread on a DIFFERENT SandboxKey must not be blocked by the shared lock.
            CountDownLatch isolatedRan = new CountDownLatch(1);
            pool.submit(() -> {
                try (var ignored = lock.acquireSandboxLock(isolated)) {
                    isolatedRan.countDown();
                }
            });

            assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
            // Different-key thread completes while same-key thread holds.
            assertThat(isolatedRan.await(2, TimeUnit.SECONDS))
                    .as("different-key thread must run in parallel")
                    .isTrue();
            firstMayLeave.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertThat(maxConcurrentShared.get()).as("same-key max concurrent").isEqualTo(1);
        assertThat(lock.activeSandboxKeys()).as("entries reaped").isZero();
    }
}
