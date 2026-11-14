package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GitRepositoryLockManagerTest extends BaseUnitTest {

    private static final RepositoryKey KEY = new RepositoryKey(100L, 1L);

    private final GitRepositoryLockManager locks = new GitRepositoryLockManager();

    @Test
    void shouldStopWaitingForTheMirrorWhenTheReaderGaveUp() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> capture = CompletableFuture.runAsync(() -> locks.withWriteLock(KEY, () -> {
            held.countDown();
            await(release);
            return null;
        }));
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
        AtomicBoolean abandoned = new AtomicBoolean();

        CompletableFuture<Object> reader =
                CompletableFuture.supplyAsync(() -> locks.withWriteLock(KEY, abandoned::get, () -> "ran"));
        Thread.sleep(300); // NOPMD - proves over this window that the reader keeps waiting for the lock
        assertThat(reader)
                .as("the reader waits while the capture holds the mirror")
                .isNotDone();
        abandoned.set(true);

        assertThatThrownBy(() -> reader.get(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS))
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(capture).as("the capture keeps the lock").isNotDone();
        release.countDown();
        capture.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shouldRunWhenTheMirrorIsFree() {
        assertThat(locks.withWriteLock(KEY, () -> false, () -> "ran")).isEqualTo("ran");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
