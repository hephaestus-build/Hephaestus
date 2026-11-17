package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryRefState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCapturePreparation;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.PinnedParks;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jdk.jfr.consumer.RecordingStream;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A capture's fetch has bounds: a provider that stops answering, and a mirror that another fetch keeps busy, end the
 * fetch as an unavailable repository. The mirror's lock is free again, and the next capture fetches as usual.
 */
class GitRepositoryFetchBoundTest extends BaseUnitTest {

    private static final RepositoryKey KEY = new RepositoryKey(1L, 42L);

    @TempDir
    Path temporary;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GitRepositoryLockManager locks = new GitRepositoryLockManager();

    @Test
    void shouldStopAFetchThatTheProviderStoppedAnsweringAndFetchAgainAtTheNextCapture() throws Exception {
        Path source = source();
        try (var provider = new SilentProvider()) {
            var git = manager(provider.port(), 1, Duration.ofMinutes(1));
            var capture = new ScmCapturePreparation();
            long started = System.nanoTime();

            capture.fetch(() -> git.ensureRepository(KEY, provider.url(), null));

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
            assertThat(capture.unavailableReason())
                    .as("a missing repository, never a claim about the work")
                    .isEqualTo(SourceAbsenceReason.REPOSITORY_FETCH_FAILED);
            assertThat(stopped("transport_silent")).isOne();

            // The lock is free: the next capture of the same repository fetches from a provider that answers.
            git.ensureRepository(KEY, source.toUri().toString(), null);
            assertThat(git.isRepositoryCloned(KEY)).isTrue();
        }
    }

    /**
     * The staging hang after a rollout: every sandbox asks the new server for its context at once, and each request
     * captures the same repository, which this server has not fetched yet. Twice as many captures as carriers wait for
     * a provider that does not answer. Other virtual threads still run, no carrier is pinned, and each capture ends as
     * an unavailable repository within the bounds.
     */
    @Test
    void shouldKeepVirtualThreadsRunningWhileManyCapturesOfOneColdRepositoryWaitForASilentProvider() throws Exception {
        int captures = 2 * Runtime.getRuntime().availableProcessors();
        List<String> pinned = new CopyOnWriteArrayList<>();
        List<ScmCapturePreparation> preparations = new ArrayList<>();
        try (var provider = new SilentProvider();
                var events = new RecordingStream()) {
            events.enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ZERO)
                    .withStackTrace();
            events.onEvent("jdk.VirtualThreadPinned", event -> {
                if (!PinnedParks.onJdkSubPoller(event.getStackTrace()))
                    pinned.add(String.valueOf(event.getStackTrace()));
            });
            events.startAsync();
            var git = manager(provider.port(), 1, Duration.ofSeconds(5));
            List<Thread> requests = new ArrayList<>();
            for (int i = 0; i < captures; i++) {
                var preparation = new ScmCapturePreparation();
                preparations.add(preparation);
                requests.add(Thread.ofVirtual()
                        .start(() -> preparation.fetch(() -> git.ensureRepository(KEY, provider.url(), null))));
            }
            // The captures wait once the provider holds a connection and every request is parked or done.
            await().atMost(Duration.ofSeconds(10))
                    .until(() -> provider.connections() > 0
                            && requests.stream()
                                    .allMatch(request -> !request.isAlive()
                                            || request.getState() == Thread.State.WAITING
                                            || request.getState() == Thread.State.TIMED_WAITING));

            var heartbeat = new CountDownLatch(1);
            Thread.ofVirtual().start(heartbeat::countDown);
            assertThat(heartbeat.await(2, TimeUnit.SECONDS))
                    .as("another virtual thread gets a carrier while the captures wait")
                    .isTrue();
            for (Thread request : requests)
                assertThat(request.join(Duration.ofSeconds(60))).isTrue();
            events.stop();
        }

        assertThat(pinned).as("pinned parks on the capture path").isEmpty();
        assertThat(preparations)
                .as("each capture records a missing repository, never a claim about the work")
                .allSatisfy(preparation -> assertThat(preparation.unavailableReason())
                        .isEqualTo(SourceAbsenceReason.REPOSITORY_FETCH_FAILED));
        assertThat(stopped("transport_silent") + stopped("mirror_busy")).isEqualTo(captures);
    }

    @Test
    void shouldStopACaptureThatAnotherFetchKeepsWaitingForTheMirror() throws Exception {
        Path source = source();
        var git = manager(0, 1, Duration.ofMillis(300));
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Thread holder = Thread.ofVirtual()
                .start(() -> locks.withWriteLock(KEY, () -> {
                    holding.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> git.ensureRepository(KEY, source.toUri().toString(), null))
                .isInstanceOf(GitRepositoryManager.RepositoryFetchException.class);
        assertThat(stopped("mirror_busy")).isOne();

        release.countDown();
        holder.join();
        git.ensureRepository(KEY, source.toUri().toString(), null);
        assertThat(git.isRepositoryCloned(KEY)).isTrue();
    }

    private GitRepositoryManager manager(int simulatorPort, int transportTimeoutSeconds, Duration mirrorWait) {
        return new GitRepositoryManager(
                new GitRepositoryProperties(true, 2, 1L << 30, 1L << 40),
                locks,
                new FabricLayout(temporary.resolve("fabric").toString()),
                simulatorPort == 0 ? GitTransportFixtures.guarded() : GitTransportFixtures.simulator(simulatorPort),
                mock(RepositoryRefState.class),
                GitTransportFixtures.copyFence(),
                meters,
                transportTimeoutSeconds,
                mirrorWait);
    }

    private double stopped(String cause) {
        return meters.counter(ScmMetrics.GIT_FETCH_STOPPED, "cause", cause).count();
    }

    private Path source() throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source"));
        try (Git git = Git.init()
                .setDirectory(source.toFile())
                .setInitialBranch("main")
                .call()) {
            Files.writeString(source.resolve("README.md"), "hello\n");
            git.add().addFilepattern("README.md").call();
            git.commit()
                    .setMessage("first")
                    .setAuthor("A", "a@example.test")
                    .setCommitter("A", "a@example.test")
                    .setSign(false)
                    .call();
        }
        return source;
    }

    /** An HTTP Git provider on loopback that accepts each connection and never sends a byte. */
    private static final class SilentProvider implements AutoCloseable {
        private final ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        SilentProvider() throws IOException {
            Thread.ofVirtual().start(() -> {
                try {
                    while (!socket.isClosed()) accepted.add(socket.accept());
                } catch (IOException closed) {
                    // The test ended.
                }
            });
        }

        int connections() {
            return accepted.size();
        }

        int port() {
            return socket.getLocalPort();
        }

        String url() {
            return "http://127.0.0.1:" + port() + "/team/repository.git";
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (Socket connection : accepted) connection.close();
        }
    }
}
