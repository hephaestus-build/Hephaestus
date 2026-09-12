package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.StreamType;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.NetworkPolicy;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.ResourceLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SecurityProfile;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Provider credentials enter only the trusted fetch process, through its private stdin stream. Each
 * operation is one container, admitted by {@link Settings#maxConcurrentOperations()} in addition to
 * the review sandboxes' own bound.
 */
public final class DockerNativeGitExecutor implements NativeGitExecutor {
    /**
     * What one worker's Git preparation is pinned to: the image, the worker that owns the mirrors it
     * creates, how many operations may run at once, the snapshot bound, and the Docker owner scope
     * shared with the review sandboxes.
     */
    public record Settings(
            String image, String workerId, int maxConcurrentOperations, long maxSnapshotBytes, String owner) {}

    private static final Logger log = LoggerFactory.getLogger(DockerNativeGitExecutor.class);

    /** Git's last {@code fatal:} line is the diagnosis, so the tail of stderr is what is kept. */
    private static final int STDERR_TAIL_BYTES = 4096;

    private static final ResourceLimits LIMITS =
            new ResourceLimits(2L * 1024 * 1024 * 1024, 2.0, 256, Duration.ofMinutes(15));

    private final Semaphore capacity;
    private final DockerClient docker;
    private final DockerClient streaming;
    private final DockerClientOperations operations;
    private final SandboxContainerManager containers;
    private final SandboxImageGuard images;
    private final ContainerSecurityPolicy policy;
    private final ObjectMapper mapper;
    private final Settings settings;
    private final String workerNamespace;

    public DockerNativeGitExecutor(
            DockerClientOperations docker,
            SandboxContainerManager containers,
            SandboxImageGuard images,
            ContainerSecurityPolicy policy,
            ObjectMapper mapper,
            Settings settings) {
        this.capacity = new Semaphore(settings.maxConcurrentOperations());
        this.docker = docker.client();
        this.streaming = docker.streamingClient();
        this.operations = docker;
        this.containers = containers;
        this.images = images;
        this.policy = policy;
        this.mapper = mapper;
        this.settings = settings;
        // A volume name admits only [a-zA-Z0-9][a-zA-Z0-9_.-]* and a worker id is any operator-chosen
        // string, so the id enters the name as its hash.
        this.workerNamespace = UUID.nameUUIDFromBytes(settings.workerId().getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    @Override
    public void execute(RepositoryKey repository, Request request, Duration timeout, OutputStream output) {
        execute(repository, null, request, timeout, output);
    }

    @Override
    public void executeInSnapshot(Path trustedRepository, Request request, Duration timeout, OutputStream output) {
        if (!request.operation().readsCanonicalEvidence())
            throw new IllegalArgumentException("Unsupported snapshot operation");
        execute(null, trustedRepository, request, timeout, output);
    }

    private void execute(
            @Nullable RepositoryKey repository,
            @Nullable Path trustedRepository,
            Request request,
            Duration timeout,
            OutputStream output) {
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Timeout must be positive");
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean admitted = false;
        try {
            admitted = capacity.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!admitted) throw new IllegalStateException("Trusted Git capacity exhausted");
            run(repository, trustedRepository, request, deadline, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git operation interrupted", e);
        } finally {
            if (admitted) capacity.release();
        }
    }

    private void run(
            @Nullable RepositoryKey repository,
            @Nullable Path trustedRepository,
            Request request,
            long deadline,
            OutputStream output)
            throws InterruptedException {
        String deadlineLabel = Instant.now().plus(remaining(deadline)).toString();
        byte[] json = mapper.writeValueAsBytes(request);
        if (json.length > MAX_REQUEST_BYTES)
            throw new IllegalArgumentException("Git request exceeds protocol frame size");
        byte[] input = Arrays.copyOf(json, json.length + 1);
        input[json.length] = '\n';
        boolean fetch = request.operation() == Operation.FETCH || request.operation() == Operation.FETCH_COMMIT;
        images.ensurePresent(settings.image());
        Map<String, String> labels = new HashMap<>();
        labels.put(SandboxLabels.GIT_OWNER, settings.owner());
        labels.put(SandboxLabels.GIT_COMPONENT, SandboxLabels.GIT_COMPONENT_PREPARATION);
        labels.put(SandboxLabels.GIT_WORKER, settings.workerId());
        labels.put(SandboxLabels.CREATED_AT, Instant.now().toString());
        List<Mount> mounts = new ArrayList<>();
        boolean mirror = false;
        String verificationVolume = "hephaestus-git-verification-" + UUID.randomUUID();
        if (trustedRepository != null) {
            operations.createVolume(verificationVolume, labels);
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(verificationVolume)
                    .withTarget("/git"));
        } else {
            var scope = Objects.requireNonNull(repository);
            String volume = "hephaestus-git-" + settings.owner() + "-" + workerNamespace + "-" + scope.workspaceId()
                    + "-" + scope.repositoryId();
            labels.put(SandboxLabels.GIT_WORKSPACE, Long.toString(scope.workspaceId()));
            labels.put(SandboxLabels.GIT_REPOSITORY, Long.toString(scope.repositoryId()));
            if (fetch) operations.createVolume(volume, labels);
            // Only a fetch creates the mirror; a query on a worker without it sees an empty tree and
            // answers "not cloned" rather than leaving an unlabelled volume behind. Docker copies the
            // image's /git into an empty named volume on its first mount, which is where the /git/lock
            // file the command below flocks comes from.
            mirror = fetch || mirrorExists(volume, scope);
            mounts.add(
                    mirror
                            ? new Mount()
                                    .withType(MountType.VOLUME)
                                    .withSource(volume)
                                    .withTarget("/git")
                                    .withReadOnly(!fetch)
                            : new Mount().withType(MountType.TMPFS).withTarget("/git"));
        }
        String snapshotVolume = "hephaestus-git-snapshot-" + UUID.randomUUID();
        boolean snapshot = request.operation() == Operation.SNAPSHOT || request.operation() == Operation.REVIEW_DIFF;
        if (snapshot) {
            operations.createVolume(snapshotVolume, labels);
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(snapshotVolume)
                    .withTarget("/snapshot"));
        }
        // The review sandbox's floor, with the provider reachable only while fetching.
        HostConfig host = operations
                .hostConfig(
                        policy.buildHostConfig(SecurityProfile.DEFAULT, LIMITS, new NetworkPolicy(fetch, null, null)))
                .withNetworkMode(fetch ? "bridge" : "none")
                .withMounts(mounts)
                .withLogConfig(new LogConfig(LogConfig.LoggingType.NONE));
        labels.put(SandboxLabels.GIT_DEADLINE, deadlineLabel);
        String container;
        try {
            container = docker.createContainerCmd(settings.image())
                    .withName("hephaestus-git-" + UUID.randomUUID())
                    .withLabels(labels)
                    .withUser("1000:1000")
                    .withEnv("GIT_MAX_SNAPSHOT_BYTES=" + settings.maxSnapshotBytes())
                    .withHostConfig(host)
                    .withStdinOpen(true)
                    .withStdInOnce(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    // The mirror's lock serialises a fetch against readers; an empty tree has nothing to lock.
                    .withCmd(
                            mirror
                                    ? new String[] {
                                        "flock",
                                        fetch ? "--exclusive" : "--shared",
                                        "/git/lock",
                                        "node",
                                        "/opt/git/operation.ts"
                                    }
                                    : new String[] {"node", "/opt/git/operation.ts"})
                    .exec()
                    .getId();
        } catch (RuntimeException failure) {
            if (snapshot) operations.removeVolume(snapshotVolume);
            if (trustedRepository != null) operations.removeVolume(verificationVolume);
            throw failure;
        }
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        var diagnostics = new ByteArrayOutputStream();
        Throwable primary = null;
        try (var stdin = new ByteArrayInputStream(input);
                var callback = new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDERR) {
                            synchronized (diagnostics) {
                                diagnostics.write(frame.getPayload(), 0, frame.getPayload().length);
                                if (diagnostics.size() > STDERR_TAIL_BYTES) {
                                    byte[] all = diagnostics.toByteArray();
                                    diagnostics.reset();
                                    diagnostics.write(all, all.length - STDERR_TAIL_BYTES, STDERR_TAIL_BYTES);
                                }
                            }
                            return;
                        }
                        if (frame.getStreamType() != StreamType.STDOUT) return;
                        try {
                            output.write(frame.getPayload());
                        } catch (IOException e) {
                            failure.compareAndSet(null, e);
                            onError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        failure.compareAndSet(null, throwable);
                        super.onError(throwable);
                    }
                }) {
            if (trustedRepository != null) copyCanonicalGit(container, trustedRepository);
            // The attach holds a docker-java connection open for the container's life inside the
            // client's synchronized blocks, so this must run on a platform thread: a virtual thread
            // would pin its carrier for the whole operation.
            streaming
                    .attachContainerCmd(container)
                    .withStdIn(stdin)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withLogs(false)
                    .withFollowStream(true)
                    .exec(callback);
            if (!callback.awaitStarted(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Git process attachment timed out");
            }
            containers.startContainer(container);
            var outcome = containers.waitForCompletion(container, remaining(deadline));
            if (outcome.timedOut()) throw new IllegalStateException("Git operation exceeded its deadline");
            if (outcome.exitCode() != 0) {
                String reason;
                synchronized (diagnostics) {
                    reason = diagnostics.toString(StandardCharsets.UTF_8).strip();
                }
                throw new IllegalStateException(
                        "Git operation failed: " + (reason.isEmpty() ? "no diagnostic" : reason));
            }
            if (!callback.awaitCompletion(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS)
                    || failure.get() != null) {
                throw new IllegalStateException("Git output stream did not complete", failure.get());
            }
        } catch (IOException e) {
            IllegalStateException wrapped = new IllegalStateException("Git output stream failed", e);
            primary = wrapped;
            throw wrapped;
        } catch (RuntimeException | InterruptedException e) {
            primary = e;
            throw e;
        } finally {
            List<Runnable> steps = new ArrayList<>();
            steps.add(() -> containers.forceRemove(container));
            if (snapshot) steps.add(() -> operations.removeVolume(snapshotVolume));
            if (trustedRepository != null) steps.add(() -> operations.removeVolume(verificationVolume));
            release(container, primary, steps);
        }
    }

    /**
     * Every step runs. A failure never replaces the operation's own: with a {@code primary} it is
     * attached as suppressed, otherwise the first one is thrown once the rest have run.
     */
    private static void release(String container, @Nullable Throwable primary, List<Runnable> steps) {
        RuntimeException leftover = null;
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException failure) {
                log.warn("Git preparation container {} could not be released fully", container, failure);
                if (primary != null) primary.addSuppressed(failure);
                else if (leftover == null) leftover = failure;
                else leftover.addSuppressed(failure);
            }
        }
        if (leftover != null) throw leftover;
    }

    private void copyCanonicalGit(String container, Path repository) throws IOException, InterruptedException {
        try (var input = new java.io.PipedInputStream(64 * 1024);
                var output = new java.io.PipedOutputStream(input)) {
            AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
            Thread producer = Thread.ofVirtual().start(() -> {
                try (var tar = new TarArchiveOutputStream(output);
                        var paths = java.nio.file.Files.walk(repository.resolve(".git"))) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    for (var iterator = paths.iterator(); iterator.hasNext(); ) {
                        Path path = iterator.next();
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                        String name = "mirror.git/"
                                + repository.resolve(".git").relativize(path).toString();
                        var entry = new TarArchiveEntry(name);
                        entry.setSize(Files.size(path));
                        entry.setUserId(1000);
                        entry.setGroupId(1000);
                        entry.setMode(0400);
                        tar.putArchiveEntry(entry);
                        Files.copy(path, tar);
                        tar.closeArchiveEntry();
                    }
                } catch (IOException | RuntimeException e) {
                    failure.set(e);
                }
            });
            try {
                docker.copyArchiveToContainerCmd(container)
                        .withRemotePath("/git")
                        .withTarInputStream(input)
                        .exec();
                producer.join();
                if (failure.get() != null) throw new IOException("Canonical Git transport failed", failure.get());
            } finally {
                producer.interrupt();
            }
        }
    }

    @Override
    public void deleteRepository(long repositoryId) {
        if (repositoryId <= 0) throw new IllegalArgumentException("Invalid repository ID");
        for (var volume : operations.listVolumes(Map.of(
                SandboxLabels.GIT_OWNER,
                settings.owner(),
                SandboxLabels.GIT_COMPONENT,
                SandboxLabels.GIT_COMPONENT_PREPARATION,
                SandboxLabels.GIT_REPOSITORY,
                Long.toString(repositoryId)))) {
            operations.removeVolume(volume.name());
        }
    }

    private boolean mirrorExists(String volume, RepositoryKey scope) {
        return operations
                .listVolumes(Map.of(
                        SandboxLabels.GIT_OWNER,
                        settings.owner(),
                        SandboxLabels.GIT_WORKSPACE,
                        Long.toString(scope.workspaceId()),
                        SandboxLabels.GIT_REPOSITORY,
                        Long.toString(scope.repositoryId())))
                .stream()
                .anyMatch(info -> info.name().equals(volume));
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IllegalStateException("Git operation deadline exceeded");
        return Duration.ofNanos(nanos);
    }
}
