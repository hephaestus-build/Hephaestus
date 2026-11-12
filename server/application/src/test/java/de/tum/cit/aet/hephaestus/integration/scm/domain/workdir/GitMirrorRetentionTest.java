package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

class GitMirrorRetentionTest extends BaseUnitTest {

    /** What each mirror holds here: three of them with their directories fill more than 80 % of the volume. */
    private static final int BLOCK = 1 << 20;

    private static final long VOLUME = 3L * BLOCK + (BLOCK / 2);
    private static final RepositoryKey OLDEST = new RepositoryKey(1L, 1L);
    private static final RepositoryKey MIDDLE = new RepositoryKey(1L, 2L);
    private static final RepositoryKey NEWEST = new RepositoryKey(2L, 3L);

    @TempDir
    private Path fabric;

    @Mock
    private RepositoryRepository repositories;

    private final GitRepositoryLockManager locks = new GitRepositoryLockManager();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    /** A full volume costs a mirror, not the Pod: the review's checkout on the same volume stays. */
    @Test
    void shouldEvictTheLeastRecentlyUsedMirrorsWhenTheVolumeNearsItsSize() throws Exception {
        mirror(OLDEST, BLOCK, 1);
        mirror(MIDDLE, BLOCK, 2);
        mirror(NEWEST, BLOCK, 3);
        Path checkout = write(fabric.resolve("jobs/1/job/attempt/checkout"), 50);

        assertThat(retention().evictLeastRecentlyUsedMirrors()).isEqualTo(1);

        assertThat(manager().localRepositories()).containsExactlyInAnyOrder(MIDDLE, NEWEST);
        assertThat(checkout).exists();
        assertThat(meters.get("git.mirror.evictions").counter().count()).isEqualTo(1);
        assertThat(meters.get("git.cache.bytes").gauge().value()).isLessThanOrEqualTo(VOLUME * 0.8);
        assertThat(meters.get("git.cache.limit.bytes").gauge().value()).isEqualTo(VOLUME);
    }

    @Test
    void shouldKeepEveryMirrorWhileTheVolumeHasRoom() throws Exception {
        mirror(OLDEST, 1, 1);
        mirror(MIDDLE, 1, 2);

        assertThat(retentionOf(VOLUME * 4).evictLeastRecentlyUsedMirrors()).isZero();

        assertThat(manager().localRepositories()).containsExactlyInAnyOrder(OLDEST, MIDDLE);
    }

    /** Evicting a mirror that was fetched moments ago would fail its capture, and it would only be fetched again. */
    @Test
    void shouldKeepAMirrorThatACaptureFetchedMomentsAgoWhenTheVolumeStaysFull() throws Exception {
        mirror(OLDEST, 3 * BLOCK, Instant.now().getEpochSecond());

        assertThat(retention().evictLeastRecentlyUsedMirrors()).isZero();

        assertThat(manager().localRepositories()).containsExactly(OLDEST);
        assertThat(meters.get("git.cache.bytes").gauge().value()).isGreaterThan(VOLUME * 0.8);
    }

    /** A fetch that ends while eviction waits for the lock makes the mirror used; eviction must see that under it. */
    @Test
    void shouldKeepAMirrorThatACaptureFetchedWhileEvictionWaitedForItsLock() throws Exception {
        mirror(OLDEST, BLOCK, 1);
        mirror(MIDDLE, BLOCK, Instant.now().getEpochSecond());
        mirror(NEWEST, BLOCK, Instant.now().getEpochSecond());
        var fetching = new CountDownLatch(1);
        var capture = CompletableFuture.runAsync(() -> locks.withWriteLock(OLDEST, () -> {
            fetching.countDown();
            try {
                Thread.sleep(500);
                Files.setLastModifiedTime(mirrorPath(OLDEST), FileTime.from(Instant.now()));
            } catch (InterruptedException | IOException e) {
                throw new IllegalStateException(e);
            }
            return null;
        }));
        assertThat(fetching.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(retention().evictLeastRecentlyUsedMirrors()).isZero();

        capture.join();
        assertThat(manager().localRepositories()).contains(OLDEST);
    }

    /** A capture that reads a mirror finishes, so the next mirror goes in its place. */
    @Test
    void shouldPassOverAMirrorThatACaptureHolds() throws Exception {
        mirror(OLDEST, BLOCK, 1);
        mirror(MIDDLE, BLOCK, 2);
        mirror(NEWEST, BLOCK, 3);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var capture = CompletableFuture.runAsync(() -> locks.withReadLock(OLDEST, () -> {
            holding.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(retention().evictLeastRecentlyUsedMirrors()).isEqualTo(1);
        } finally {
            release.countDown();
            capture.join();
        }

        assertThat(manager().localRepositories()).containsExactlyInAnyOrder(OLDEST, NEWEST);
    }

    private GitMirrorRetention retention() {
        return retentionOf(VOLUME);
    }

    private GitMirrorRetention retentionOf(long volume) {
        return new GitMirrorRetention(manager(), repositories, properties(volume), meters);
    }

    private GitRepositoryManager manager() {
        return new GitRepositoryManager(
                properties(VOLUME),
                locks,
                new FabricLayout(fabric.toString()),
                GitTransportFixtures.guarded(),
                mock(RepositoryRefState.class),
                GitTransportFixtures.copyFence());
    }

    private static GitRepositoryProperties properties(long volume) {
        return new GitRepositoryProperties(true, 1, 1024, volume);
    }

    private void mirror(RepositoryKey repository, int bytes, long lastUsedSecond) throws IOException {
        Path mirror = mirrorPath(repository);
        write(mirror.resolve("objects/pack/pack-1.pack"), bytes);
        Files.setLastModifiedTime(mirror, FileTime.from(Instant.ofEpochSecond(lastUsedSecond)));
    }

    private Path mirrorPath(RepositoryKey repository) {
        return fabric.resolve("mirrors")
                .resolve(Long.toString(repository.workspaceId()))
                .resolve(repository.repositoryId() + ".git");
    }

    private static Path write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[bytes]);
    }
}
