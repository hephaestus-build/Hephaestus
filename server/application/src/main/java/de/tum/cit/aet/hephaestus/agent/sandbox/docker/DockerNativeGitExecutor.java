package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.StreamType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * Provider credentials enter only the trusted fetch process, through its private stdin stream. Each
 * operation is one container, so the sandbox's container bound is what limits concurrent Git work.
 */
public final class DockerNativeGitExecutor implements NativeGitExecutor {
    /**
     * What one worker's Git preparation is pinned to: the image, the worker that owns the mirrors it
     * creates, how many operations may run at once, and the Docker owner scope and OCI runtime shared
     * with the review sandboxes.
     */
    public record Settings(
            String image,
            String workerId,
            int maxConcurrentOperations,
            String owner,
            @Nullable String containerRuntime) {}

    private final Semaphore capacity;
    private final DockerClient docker;
    private final DockerClient streaming;
    private final DockerVolumeOperations volumes;
    private final SandboxContainerManager containers;
    private final SandboxImageGuard images;
    private final ObjectMapper mapper;
    private final Settings settings;
    private final String workerNamespace;

    public DockerNativeGitExecutor(
            DockerClientOperations docker,
            SandboxContainerManager containers,
            SandboxImageGuard images,
            ObjectMapper mapper,
            Settings settings) {
        this.capacity = new Semaphore(settings.maxConcurrentOperations());
        this.docker = docker.client();
        this.streaming = docker.streamingClient();
        this.volumes = docker;
        this.containers = containers;
        this.images = images;
        this.mapper = mapper;
        this.settings = settings;
        this.workerNamespace = UUID.nameUUIDFromBytes(
                        settings.workerId().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    @Override
    public void execute(RepositoryKey repository, Request request, Duration timeout, OutputStream output) {
        execute(repository, null, request, timeout, output);
    }

    @Override
    public void executeInSnapshot(Path trustedRepository, Request request, Duration timeout, OutputStream output) {
        if (request.operation() != Operation.CITED_BLOBS
                && request.operation() != Operation.HISTORICAL_BLOB
                && request.operation() != Operation.SCAN_SECRETS)
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
        String deadlineLabel = java.time.Instant.now().plus(remaining(deadline)).toString();
        byte[] input = mapper.writeValueAsBytes(request);
        if (input.length > 64 * 1024) throw new IllegalArgumentException("Git request exceeds protocol frame size");
        boolean fetch = request.operation() == Operation.FETCH || request.operation() == Operation.FETCH_COMMIT;
        images.ensurePresent(settings.image());
        Map<String, String> labels = new java.util.HashMap<>();
        labels.put("hephaestus.owner", settings.owner());
        labels.put("hephaestus.component", "git-preparation");
        labels.put("hephaestus.worker", settings.workerId());
        labels.put("hephaestus.created-at", java.time.Instant.now().toString());
        List<Mount> mounts = new java.util.ArrayList<>();
        String verificationVolume = "hephaestus-git-verification-" + UUID.randomUUID();
        if (trustedRepository != null) {
            volumes.createVolume(verificationVolume, labels);
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(verificationVolume)
                    .withTarget("/git"));
        } else {
            var scope = java.util.Objects.requireNonNull(repository);
            String volume = "hephaestus-git-" + settings.owner() + "-" + workerNamespace + "-" + scope.workspaceId()
                    + "-" + scope.repositoryId();
            labels.put("hephaestus.workspace", Long.toString(scope.workspaceId()));
            labels.put("hephaestus.repository", Long.toString(scope.repositoryId()));
            volumes.createVolume(volume, labels);
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(volume)
                    .withTarget("/git")
                    .withReadOnly(!fetch));
        }
        String snapshotVolume = "hephaestus-git-snapshot-" + UUID.randomUUID();
        boolean snapshot = request.operation() == Operation.SNAPSHOT || request.operation() == Operation.REVIEW_DIFF;
        if (snapshot) {
            volumes.createVolume(snapshotVolume, labels);
            mounts.add(new Mount()
                    .withType(MountType.VOLUME)
                    .withSource(snapshotVolume)
                    .withTarget("/snapshot"));
        }
        HostConfig host = HostConfig.newHostConfig()
                .withNetworkMode(fetch ? "bridge" : "none")
                .withReadonlyRootfs(true)
                .withPrivileged(false)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withMemory(2L * 1024 * 1024 * 1024)
                .withMemorySwap(2L * 1024 * 1024 * 1024)
                .withNanoCPUs(2_000_000_000L)
                .withPidsLimit(256L)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,noexec,size=512m,uid=1000,gid=1000,mode=700"))
                .withMounts(mounts)
                .withLogConfig(new LogConfig(LogConfig.LoggingType.NONE));
        if (settings.containerRuntime() != null) host.withRuntime(settings.containerRuntime());
        labels.put("hephaestus.deadline", deadlineLabel);
        String container;
        try {
            container = docker.createContainerCmd(settings.image())
                    .withName("hephaestus-git-" + UUID.randomUUID())
                    .withLabels(labels)
                    .withUser("1000:1000")
                    .withHostConfig(host)
                    .withStdinOpen(true)
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withCmd(
                            trustedRepository == null
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
            if (snapshot) volumes.removeVolume(snapshotVolume);
            if (trustedRepository != null) volumes.removeVolume(verificationVolume);
            throw failure;
        }
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        try (var stdin = new ByteArrayInputStream(input);
                var callback = new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
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
            if (outcome.timedOut() || outcome.exitCode() != 0)
                throw new IllegalStateException("Git operation failed or exhausted its resources");
            if (!callback.awaitCompletion(remaining(deadline).toMillis(), TimeUnit.MILLISECONDS)
                    || failure.get() != null) {
                throw new IllegalStateException("Git output stream did not complete", failure.get());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Git output stream failed", e);
        } finally {
            try {
                containers.forceRemove(container);
            } finally {
                if (snapshot) volumes.removeVolume(snapshotVolume);
                if (trustedRepository != null) volumes.removeVolume(verificationVolume);
            }
        }
    }

    private void copyCanonicalGit(String container, Path repository) throws IOException, InterruptedException {
        try (var input = new java.io.PipedInputStream(64 * 1024);
                var output = new java.io.PipedOutputStream(input)) {
            AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
            Thread producer = Thread.ofVirtual().start(() -> {
                try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(output);
                        var paths = java.nio.file.Files.walk(repository.resolve(".git"))) {
                    tar.setLongFileMode(
                            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);
                    for (var iterator = paths.iterator(); iterator.hasNext(); ) {
                        Path path = iterator.next();
                        if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                        String name = "mirror.git/"
                                + repository.resolve(".git").relativize(path).toString();
                        var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(name);
                        entry.setSize(java.nio.file.Files.size(path));
                        entry.setUserId(1000);
                        entry.setGroupId(1000);
                        entry.setMode(0400);
                        tar.putArchiveEntry(entry);
                        java.nio.file.Files.copy(path, tar);
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
        for (var volume : volumes.listVolumes(Map.of(
                "hephaestus.owner",
                settings.owner(),
                "hephaestus.component",
                "git-preparation",
                "hephaestus.repository",
                Long.toString(repositoryId)))) {
            volumes.removeVolume(volume.name());
        }
    }

    private static Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IllegalStateException("Git operation deadline exceeded");
        return Duration.ofNanos(nanos);
    }
}
