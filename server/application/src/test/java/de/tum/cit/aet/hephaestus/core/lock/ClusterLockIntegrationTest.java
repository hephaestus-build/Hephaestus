package de.tum.cit.aet.hephaestus.core.lock;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The lock against PostgreSQL: one holder per name across sessions, a wait for a release, and no lock left behind. */
class ClusterLockIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ClusterLock locks;

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldRefuseTheLockWhileAnotherSessionHoldsIt() throws Exception {
        String name = "test:" + UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> locks.run(name, Duration.ZERO, () -> {
                    held.countDown();
                    await(release);
                }));
        try {
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(locks.call(name, Duration.ZERO, () -> "ran")).isEmpty();
            assertThat(locks.call("test:" + UUID.randomUUID(), Duration.ZERO, () -> "ran"))
                    .contains("ran");
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(10));
        }
        assertThat(locks.call(name, Duration.ZERO, () -> "ran")).contains("ran");
    }

    @Test
    void shouldRunOnceTheHolderReleasesWithinTheWait() throws Exception {
        String name = "test:" + UUID.randomUUID();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> locks.run(name, Duration.ZERO, () -> {
                    held.countDown();
                    await(release);
                }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.ofVirtual().start(() -> {
            sleep(Duration.ofMillis(500));
            release.countDown();
        });
        assertThat(locks.call(name, Duration.ofSeconds(10), () -> "ran")).contains("ran");
        holder.join(Duration.ofSeconds(10));
    }

    @Test
    void shouldFreeTheLockWhenItsSessionEnds() throws Exception {
        String name = "test:" + UUID.randomUUID();
        // A server that dies mid-work: its session ends without an unlock.
        try (Connection dying = dataSource.getConnection();
                PreparedStatement lock = dying.prepareStatement(
                        "SELECT pg_advisory_lock(hashtext('hephaestus-cluster-lock'), hashtext(?))")) {
            lock.setString(1, name);
            lock.execute();
            assertThat(locks.call(name, Duration.ZERO, () -> "ran")).isEmpty();
            dying.abort(Runnable::run);
        }
        assertThat(locks.call(name, Duration.ofSeconds(5), () -> "ran")).contains("ran");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
