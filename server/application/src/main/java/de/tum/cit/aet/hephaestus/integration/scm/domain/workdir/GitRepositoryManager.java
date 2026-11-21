package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * Bare mirrors under the fabric root, one per workspace and repository: their fetches, and the reads and commit walks
 * that run on them under the mirror's read lock. {@link GitTreeSnapshots} writes the checkouts that a review reads
 * from them, and {@link GitMirrorRetention} removes them. Every operation runs in this process; a role that runs Git
 * owns its own mirrors.
 */
@Service
@EnableConfigurationProperties(GitRepositoryProperties.class)
public class GitRepositoryManager {
    /** Where a fetched review head lives in the mirror; content-addressed, so a refetch is idempotent. */
    public static final String REVIEW_REFS = "refs/hephaestus/reviews/";

    /** What the review container may see of the mirror's refs, with their types; the admission reads it back. */
    public static final String CAPTURED_REFS = "hephaestus-captured-refs";

    static final String MIRRORS = "mirrors";

    private static final Logger log = LoggerFactory.getLogger(GitRepositoryManager.class);

    private static final List<RefSpec> MIRROR_REFSPECS =
            List.of(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*"));

    private final GitRepositoryProperties properties;
    private final GitRepositoryLockManager locks;
    private final FabricLayout layout;
    private final GitCommitWalks walks;

    private final GuardedGitTransport transports;
    private final int transportTimeoutSeconds;
    private final Duration captureMirrorWait;
    private final Counter transportSilent;
    private final Counter mirrorBusy;

    @Autowired
    public GitRepositoryManager(
            GitRepositoryProperties properties,
            GitRepositoryLockManager locks,
            FabricLayout layout,
            GuardedGitTransport transports,
            MeterRegistry meters) {
        this(properties, locks, layout, transports, meters, TRANSPORT_TIMEOUT_SECONDS, CAPTURE_MIRROR_WAIT);
    }

    /**
     * @param transportTimeoutSeconds how long a fetch may wait for the provider in one read or write
     * @param captureMirrorWait how long a capture waits for another fetch of the same mirror
     */
    GitRepositoryManager(
            GitRepositoryProperties properties,
            GitRepositoryLockManager locks,
            FabricLayout layout,
            GuardedGitTransport transports,
            MeterRegistry meters,
            int transportTimeoutSeconds,
            Duration captureMirrorWait) {
        this.transports = transports;
        this.properties = properties;
        this.locks = locks;
        this.layout = layout;
        this.walks = new GitCommitWalks(properties.maxConcurrentIngestions());
        this.transportTimeoutSeconds = transportTimeoutSeconds;
        this.captureMirrorWait = captureMirrorWait;
        this.transportSilent = fetchStopped(meters, "transport_silent");
        this.mirrorBusy = fetchStopped(meters, "mirror_busy");
    }

    private static Counter fetchStopped(MeterRegistry meters, String cause) {
        return Counter.builder(ScmMetrics.GIT_FETCH_STOPPED)
                .description("Fetches that stopped because the provider went silent or the mirror stayed busy")
                .tag("cause", cause)
                .register(meters);
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    public boolean isRepositoryCloned(RepositoryKey repository) {
        return isEnabled() && Files.isRegularFile(mirror(repository).resolve(Constants.HEAD));
    }

    /**
     * Fetches the mirror for a capture. It waits at most {@link #CAPTURE_MIRROR_WAIT} for the mirror's lock, and the
     * fetch fails when the provider is silent for {@link #TRANSPORT_TIMEOUT_SECONDS}. Either way the lock is released
     * and the call throws {@link RepositoryFetchException}: the capture records the repository as unavailable, and the
     * next capture of this repository fetches again.
     */
    public void ensureRepository(RepositoryKey repository, String cloneUrl, @Nullable String token) {
        long deadline = System.nanoTime() + captureMirrorWait.toNanos();
        try {
            ensure(repository, cloneUrl, token, () -> System.nanoTime() - deadline > 0, transportTimeoutSeconds);
        } catch (CancellationException busy) {
            stopped(mirrorBusy, repository, "mirror_busy");
            throw new RepositoryFetchException("The mirror stayed busy with another fetch", busy);
        }
    }

    /**
     * How long any fetch may wait for the provider in one read or write, in seconds. A provider that streams a large
     * pack keeps the transport busy, and one that says nothing for this long has stopped; the fetch then fails and
     * releases the mirror's lock.
     */
    public static final int TRANSPORT_TIMEOUT_SECONDS = 60;

    /**
     * How long a capture waits for another fetch of the same mirror. Every fetch stops after {@link
     * #TRANSPORT_TIMEOUT_SECONDS} of silence, so the wait covers only fetches that still receive data, such as the
     * first fetch of a large repository.
     */
    public static final Duration CAPTURE_MIRROR_WAIT = Duration.ofMinutes(5);

    /**
     * As {@link #ensureRepository(RepositoryKey, String, String)}, for a reader that may stop waiting: when
     * {@code abandoned} holds once the mirror's lock is taken, nothing is fetched and the call throws
     * {@link CancellationException}. Its fetch fails when the provider is silent for
     * {@link #TRANSPORT_TIMEOUT_SECONDS}, so it releases the mirror's lock.
     */
    public void ensureRepository(
            RepositoryKey repository, String cloneUrl, @Nullable String token, BooleanSupplier abandoned) {
        ensure(repository, cloneUrl, token, abandoned, transportTimeoutSeconds);
    }

    /** @param timeoutSeconds JGit's transport timeout: how long one read or write may wait for the provider */
    private void ensure(
            RepositoryKey repository,
            String cloneUrl,
            @Nullable String token,
            BooleanSupplier abandoned,
            int timeoutSeconds) {
        requireEnabled();
        locks.withWriteLock(repository, abandoned, () -> {
            if (abandoned.getAsBoolean()) throw new CancellationException("The reader stopped waiting for the mirror");
            Path mirror = mirror(repository);
            try {
                if (!isRepositoryCloned(repository) || !hasOrigin(mirror, cloneUrl)) {
                    // A mirror is a cache keyed by ids a restore can reuse for another
                    // upstream: rebuild
                    // rather than retarget, so no old objects survive.
                    FileUtils.delete(mirror.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
                    Files.createDirectories(mirror.getParent());
                    try (Git git = Git.init()
                            .setBare(true)
                            .setDirectory(mirror.toFile())
                            .call()) {
                        StoredConfig config = git.getRepository().getConfig();
                        config.setString("remote", "origin", "url", cloneUrl);
                        // A redirect would carry the credential to a host the clone URL
                        // never named.
                        config.setBoolean("http", null, "followRedirects", false);
                        // A checkout may pin a commit no ref reaches any more; it is
                        // fetched from the mirror by id.
                        config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
                        config.save();
                    }
                }
                try (Git git = Git.open(mirror.toFile())) {
                    git.fetch()
                            .setRemote("origin")
                            .setRefSpecs(MIRROR_REFSPECS)
                            .setTagOpt(TagOpt.NO_TAGS)
                            .setRemoveDeletedRefs(true)
                            .setCredentialsProvider(credentials(token))
                            .setTransportConfigCallback(transports.callback(cloneUrl))
                            .setTimeout(timeoutSeconds)
                            .call();
                }
                // A fetch that brings nothing new changes no file, and eviction reads the last use from here.
                Files.setLastModifiedTime(mirror, FileTime.from(Instant.now()));
                return null;
            } catch (InvalidRemoteException | TransportException e) {
                if (silent(e)) stopped(transportSilent, repository, "transport_silent");
                throw new RepositoryFetchException(e);
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch repository " + repository.repositoryId(), e);
            }
        });
    }

    /** Whether the fetch stopped because the provider said nothing for the transport timeout. */
    private static boolean silent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof InterruptedIOException) return true;
        return false;
    }

    private void stopped(Counter counter, RepositoryKey repository, String cause) {
        counter.increment();
        log.atWarn()
                .addKeyValue("event.name", "scm.mirror.fetch.stopped")
                .addKeyValue("git.fetch.stop_cause", cause)
                .addKeyValue("workspace.id", repository.workspaceId())
                .addKeyValue("repository.id", repository.repositoryId())
                .log("A mirror fetch stopped; its capture records the repository as unavailable");
    }

    /**
     * Fetches the provider's review ref for a head no branch reaches, such as a fork's, and pins it. The fetch fails
     * when the provider is silent for {@link #TRANSPORT_TIMEOUT_SECONDS}, so it releases the mirror's lock.
     */
    public boolean fetchRemoteCommit(
            RepositoryKey repository, String cloneUrl, String remoteRef, String expectedSha, @Nullable String token) {
        int timeoutSeconds = transportTimeoutSeconds;
        requireEnabled();
        if (!Repository.isValidRefName(remoteRef) || !ObjectId.isId(expectedSha)) {
            throw new IllegalArgumentException("Invalid remote review ref or expected commit SHA");
        }
        return locks.withWriteLock(repository, () -> {
            String localRef = REVIEW_REFS + expectedSha.toLowerCase(Locale.ROOT);
            try (Git git = Git.open(mirror(repository).toFile())) {
                if (!hasOrigin(mirror(repository), cloneUrl)) throw new IOException("Mirror origin differs");
                git.fetch()
                        .setRemote("origin")
                        .setRefSpecs(new RefSpec("+" + remoteRef + ":" + localRef))
                        .setTagOpt(TagOpt.NO_TAGS)
                        .setCredentialsProvider(credentials(token))
                        .setTransportConfigCallback(transports.callback(cloneUrl))
                        .setTimeout(timeoutSeconds)
                        .call();
                ObjectId resolved = git.getRepository().resolve(localRef);
                return resolved != null && resolved.getName().equals(expectedSha.toLowerCase(Locale.ROOT));
            } catch (InvalidRemoteException | TransportException e) {
                if (silent(e)) stopped(transportSilent, repository, "transport_silent");
                throw new RepositoryFetchException(e);
            } catch (GitAPIException | IOException e) {
                throw new GitOperationException("Cannot fetch review commit " + expectedSha, e);
            }
        });
    }

    public @Nullable String resolveBranchHead(RepositoryKey repository, String branch) {
        if (!isEnabled() || !Repository.isValidRefName(Constants.R_HEADS + branch)) return null;
        return read(repository, repo -> {
            Ref ref = repo.exactRef(Constants.R_HEADS + branch);
            return ref == null || ref.getObjectId() == null
                    ? null
                    : ref.getObjectId().getName();
        });
    }

    /** The commit a tag names, peeled; null when the tag is absent or names no commit. */
    public @Nullable String resolveTag(RepositoryKey repository, String tag) {
        if (!isEnabled() || !Repository.isValidRefName(Constants.R_TAGS + tag)) return null;
        return read(repository, repo -> {
            Ref ref = repo.exactRef(Constants.R_TAGS + tag);
            if (ref == null || ref.getObjectId() == null) return null;
            try (RevWalk walk = new RevWalk(repo)) {
                return walk.parseCommit(ref.getObjectId()).getName();
            } catch (IncorrectObjectTypeException e) {
                return null;
            }
        });
    }

    public boolean commitExists(RepositoryKey repository, String sha) {
        if (!isEnabled() || !ObjectId.isId(sha)) return false;
        return Boolean.TRUE.equals(read(repository, repo -> {
            try (RevWalk walk = new RevWalk(repo)) {
                walk.parseCommit(ObjectId.fromString(sha));
                return true;
            } catch (IOException e) {
                return false;
            }
        }));
    }

    /** {@link RepositoryDiff#reviewBase} on the mirror; null when the range is not a review. */
    public @Nullable String reviewBase(RepositoryKey repository, String targetSha, String headSha) {
        if (!isEnabled() || !ObjectId.isId(targetSha) || !ObjectId.isId(headSha)) return null;
        return read(repository, repo -> {
            ObjectId base =
                    RepositoryDiff.reviewBase(repo, ObjectId.fromString(targetSha), ObjectId.fromString(headSha));
            return base == null ? null : base.getName();
        });
    }

    /** {@link RepositoryDiff#changedPaths} on the mirror. */
    public Set<String> changedPaths(RepositoryKey repository, String baseSha, String headSha) {
        Set<String> paths = read(
                repository,
                repo -> RepositoryDiff.changedPaths(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return paths == null ? Set.of() : paths;
    }

    /** {@link RepositoryDiff#unifiedDiff} on the mirror. */
    public String unifiedDiff(RepositoryKey repository, String baseSha, String headSha) {
        String text = read(
                repository,
                repo -> RepositoryDiff.unifiedDiff(repo, ObjectId.fromString(baseSha), ObjectId.fromString(headSha)));
        return text == null ? "" : text;
    }

    public void forEachCommitInRange(
            RepositoryKey repository,
            @Nullable String fromSha,
            String toSha,
            Function<List<String>, Set<String>> captured,
            Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            walks.forEachCommitInRange(repo, fromSha, toSha, captured, consumer);
            return null;
        });
    }

    /**
     * Hands every commit of {@code base..head} to {@code consumer} with its file changes, in no promised order: the
     * whole range, which a staged record of a change must hold. The walk streams, so the caller's byte budget can stop
     * it at the first commit over it; each commit's message is read as it is handed on and released after, so the
     * messages of the range are never held together.
     *
     * @throws IllegalStateException when local git storage is disabled or the mirror does not hold either end, so
     *     a range that could not be read is never mistaken for an empty one
     */
    public void forEachCommitBetween(
            RepositoryKey repository, String base, String head, Consumer<CommitDetails> consumer) {
        if (!isEnabled()) {
            throw new IllegalStateException("Local git storage is disabled; the commits of a range cannot be read");
        }
        read(repository, repo -> {
            walks.forEachCommitBetween(repo, base, head, consumer);
            return null;
        });
    }

    /** The full message, subject and body, of every commit in {@code base..head}. */
    public void forEachCommitMessage(RepositoryKey repository, String base, String head, Consumer<String> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            walks.forEachCommitMessage(repo, base, head, consumer);
            return null;
        });
    }

    /** Every commit reachable from any ref; completion markers skip individual SHAs, never ancestor ranges. */
    public void forEachMissingCommit(
            RepositoryKey repository, Function<List<String>, Set<String>> captured, Consumer<CommitDetails> consumer) {
        if (!isEnabled()) return;
        read(repository, repo -> {
            walks.forEachMissingCommit(repo, captured, consumer);
            return null;
        });
    }

    /** A read of the mirror's object and ref databases. */
    public interface MirrorRead<T> {
        @Nullable
        T apply(Repository repo) throws IOException;
    }

    /** Runs a read against the mirror under its read lock; a fetch cannot rewrite refs underneath it. */
    public <T> @Nullable T read(RepositoryKey repository, MirrorRead<T> operation) {
        return locks.withReadLock(repository, () -> {
            try (Git git = Git.open(mirror(repository).toFile())) {
                return operation.apply(git.getRepository());
            } catch (IOException e) {
                throw new GitOperationException("Cannot read repository " + repository.repositoryId(), e);
            }
        });
    }

    Path mirror(RepositoryKey repository) {
        return layout.root()
                .resolve(MIRRORS)
                .resolve(Long.toString(repository.workspaceId()))
                .resolve(repository.repositoryId() + ".git");
    }

    /** JGit fetches from the first configured URL but reads back the last; a stale value ahead of the current one is a change. */
    private static boolean hasOrigin(Path mirror, String cloneUrl) throws IOException {
        try (Git git = Git.open(mirror.toFile())) {
            String[] urls = git.getRepository().getConfig().getStringList("remote", "origin", "url");
            return urls.length == 1 && cloneUrl.equals(urls[0]);
        }
    }

    private static @Nullable UsernamePasswordCredentialsProvider credentials(@Nullable String token) {
        return token == null || token.isBlank() ? null : new UsernamePasswordCredentialsProvider("oauth2", token);
    }

    void requireEnabled() {
        if (!isEnabled()) throw new IllegalStateException("Git repository preparation is disabled");
    }

    /** The authorized upstream could not supply Git objects. Local storage failures stay distinct. */
    public static final class RepositoryFetchException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public RepositoryFetchException(GitAPIException cause) {
            super("Repository Git transport is unavailable", cause);
        }

        RepositoryFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A built checkout whose commit has another tree than the capture recorded. */
    public static final class TreeMismatchException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public TreeMismatchException() {
            super("The built checkout has another tree than the capture recorded", new IOException());
        }
    }

    /** The mirror is not on this server, as after an eviction: fetching it again makes it available. */
    public static final class MirrorUnavailableException extends GitOperationException {
        @Serial
        private static final long serialVersionUID = 1L;

        public MirrorUnavailableException() {
            super("The repository mirror is not on this server", new IOException());
        }
    }

    public static class GitOperationException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public GitOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
