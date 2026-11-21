package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager.GitOperationException;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.eclipse.jgit.util.FileUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Removes the mirrors under this process's root that their workspace no longer keeps. Each server holds its own
 * root, so each one reconciles its own; a server that was offline catches up on its next pass. A
 * workspace keeps a mirror while it monitors the repository or still indexes it, as a stopped GitLab project is kept
 * until its work is removed. Repository removal and workspace purge therefore reach every role's mirrors.
 *
 * <p>A kept mirror is still a cache. When the volume nears its budget, the mirrors that a capture used longest ago go too,
 * because the kubelet would otherwise evict the whole Pod with its running attempts, and the next Pod would fill the same
 * mirrors again. Its next capture fetches it again, as after a restart.
 *
 * <p>It also holds the inventory of those mirrors: which ones are on the volume, when a capture last used each one,
 * what they take on disk, and the deletion of one mirror under its write lock.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Walks every workspace's mirrors; each check filters on the mirror's own workspace")
public class GitMirrorRetention {
    private static final Logger log = LoggerFactory.getLogger(GitMirrorRetention.class);
    private static final Pattern MIRROR_ID = Pattern.compile("[1-9][0-9]{0,17}");

    /** The block size of the file systems a node's volumes use. */
    private static final long DISK_BLOCK = 4096;

    /** A mirror whose lock a fetch holds longer than this waits for the next pass. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);

    /** Leaves room for what a pass cannot see coming: a fetch or a checkout between two passes. */
    private static final double EVICTION_SHARE = 0.8;

    /**
     * A capture reads a mirror after it fetched it, and a mirror that takes most of the volume would otherwise be
     * evicted and fetched again on every pass.
     */
    private static final Duration IN_USE_FOR = Duration.ofMinutes(10);

    private final GitRepositoryManager mirrors;
    private final GitRepositoryLockManager locks;
    private final FabricLayout layout;
    private final RepositoryRepository repositories;
    private final long maxCacheBytes;
    private final AtomicLong cacheBytes = new AtomicLong();
    private final Counter evictions;

    public GitMirrorRetention(
            GitRepositoryManager mirrors,
            GitRepositoryLockManager locks,
            FabricLayout layout,
            RepositoryRepository repositories,
            GitRepositoryProperties properties,
            MeterRegistry meterRegistry) {
        this.mirrors = mirrors;
        this.locks = locks;
        this.layout = layout;
        this.repositories = repositories;
        this.maxCacheBytes = properties.maxCacheBytes();
        Gauge.builder(ScmMetrics.GIT_CACHE_BYTES, cacheBytes, AtomicLong::get)
                .description("Disk used on the volume of Git mirrors, review checkouts and staged archives")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder(ScmMetrics.GIT_CACHE_LIMIT_BYTES, () -> maxCacheBytes)
                .description(
                        "The eviction budget on the volume: its size minus the room kept for the snapshots that run at once")
                .baseUnit("bytes")
                .register(meterRegistry);
        this.evictions = Counter.builder(ScmMetrics.GIT_MIRROR_EVICTIONS)
                .description("Kept Git mirrors deleted because their volume neared its budget")
                .register(meterRegistry);
    }

    /** @return how many mirrors were deleted */
    public int removeUnretainedMirrors() {
        if (!mirrors.isEnabled()) return 0;
        int removed = 0;
        for (RepositoryKey repository : localRepositories()) {
            try {
                // Most mirrors are kept; check before waiting for a mirror lock that a long fetch may hold.
                if (isRetained(repository)) continue;
                if (deleteUnretainedRepository(repository, LOCK_WAIT, () -> isRetained(repository))) removed++;
            } catch (RuntimeException e) {
                // One mirror that cannot be removed now must not keep the others; the next pass retries it.
                log.warn(
                        "Mirror removal failed: workspaceId={}, repositoryId={}",
                        repository.workspaceId(),
                        repository.repositoryId(),
                        e);
            }
        }
        return removed;
    }

    /** @return how many mirrors were deleted */
    public int evictLeastRecentlyUsedMirrors() {
        if (!mirrors.isEnabled()) return 0;
        long used = cacheBytes();
        long allowed = (long) (maxCacheBytes * EVICTION_SHARE);
        cacheBytes.set(used);
        Instant inUseSince = Instant.now().minus(IN_USE_FOR);
        int evicted = 0;
        if (used > allowed) {
            for (RepositoryKey repository : localRepositoriesLeastRecentlyUsedFirst()) {
                if (used <= allowed || lastUsed(repository).isAfter(inUseSince)) break;
                long size;
                try {
                    size = mirrorBytes(repository);
                    // Checked again under the lock: a capture's fetch that this pass waited for has just used the
                    // mirror.
                    if (!deleteUnretainedRepository(
                            repository, LOCK_WAIT, () -> lastUsed(repository).isAfter(inUseSince))) continue;
                } catch (RuntimeException e) {
                    log.warn(
                            "Mirror eviction failed: workspaceId={}, repositoryId={}",
                            repository.workspaceId(),
                            repository.repositoryId(),
                            e);
                    continue;
                }
                used -= size;
                evicted++;
                evictions.increment();
            }
        }
        cacheBytes.set(used);
        return evicted;
    }

    private boolean isRetained(RepositoryKey repository) {
        return repositories.isMonitored(repository.workspaceId(), repository.repositoryId())
                || repositories.isIndexedByWorkspace(repository.workspaceId(), repository.repositoryId());
    }

    /** The mirrors under this process's root, whichever workspace they belong to. */
    List<RepositoryKey> localRepositories() {
        Path root = layout.root().resolve(GitRepositoryManager.MIRRORS);
        if (!Files.isDirectory(root)) return List.of();
        List<RepositoryKey> keys = new ArrayList<>();
        try (Stream<Path> workspaces = Files.list(root)) {
            for (Path workspace : workspaces.toList()) {
                String workspaceId = workspace.getFileName().toString();
                if (!MIRROR_ID.matcher(workspaceId).matches() || !Files.isDirectory(workspace, NOFOLLOW_LINKS))
                    continue;
                try (Stream<Path> mirrors = Files.list(workspace)) {
                    for (Path mirror : mirrors.toList()) {
                        String name = mirror.getFileName().toString();
                        if (!name.endsWith(".git")) continue;
                        String repositoryId = name.substring(0, name.length() - ".git".length());
                        if (MIRROR_ID.matcher(repositoryId).matches() && Files.isDirectory(mirror, NOFOLLOW_LINKS))
                            keys.add(new RepositoryKey(Long.parseLong(workspaceId), Long.parseLong(repositoryId)));
                    }
                }
            }
        } catch (IOException e) {
            throw new GitOperationException("Cannot list repository mirrors", e);
        }
        return List.copyOf(keys);
    }

    /** The mirrors under this process's root, the one that a capture used longest ago first. */
    List<RepositoryKey> localRepositoriesLeastRecentlyUsedFirst() {
        return localRepositories().stream()
                .sorted(Comparator.comparing(this::lastUsed))
                .toList();
    }

    /**
     * When a capture last fetched this mirror; the epoch for a mirror that is already gone. A read does not count: it
     * holds the read lock, which keeps the mirror while it runs.
     */
    Instant lastUsed(RepositoryKey repository) {
        try {
            return Files.getLastModifiedTime(mirrors.mirror(repository), NOFOLLOW_LINKS)
                    .toInstant();
        } catch (IOException removed) {
            return Instant.EPOCH;
        }
    }

    /** What this process keeps on its volume: mirrors, attempt folders, snapshot spools and staged archives. */
    long cacheBytes() {
        return bytesUnder(layout.root());
    }

    long mirrorBytes(RepositoryKey repository) {
        return bytesUnder(mirrors.mirror(repository));
    }

    /**
     * What these files take on disk, in whole blocks with their directories, as the kubelet counts a volume. Counts what
     * it can read: a fetch or an attempt may remove a file under the walk.
     */
    private static long bytesUnder(Path root) {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return 0;
        var bytes = new AtomicLong();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    bytes.addAndGet(DISK_BLOCK);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    bytes.addAndGet((attributes.size() + DISK_BLOCK - 1) / DISK_BLOCK * DISK_BLOCK);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, @Nullable IOException failure) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new GitOperationException("Cannot measure " + root.getFileName(), e);
        }
        return bytes.get();
    }

    /**
     * Deletes one workspace's mirror unless {@code retained} holds. The check runs under the mirror's write lock, so a
     * fetch or a read that started before it finishes first, and one that starts after it finds no mirror. A fetch can
     * run for minutes while it receives data, so this waits at most {@code wait} for the lock and then leaves the
     * mirror for a later call.
     *
     * @return whether the mirror was deleted
     */
    boolean deleteUnretainedRepository(RepositoryKey repository, Duration wait, BooleanSupplier retained) {
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            return locks.withWriteLock(repository, () -> System.nanoTime() - deadline > 0, () -> {
                if (retained.getAsBoolean()) return false;
                try {
                    // A fetch that just finished may still be packing in the background; a file it removes under
                    // the walk is not a failure to delete.
                    FileUtils.delete(
                            mirrors.mirror(repository).toFile(),
                            FileUtils.RECURSIVE | FileUtils.RETRY | FileUtils.SKIP_MISSING);
                } catch (IOException e) {
                    throw new GitOperationException("Cannot delete repository mirror", e);
                }
                return true;
            });
        } catch (CancellationException busy) {
            return false;
        }
    }

    /** Its own scheduler thread: a sweep must not wait on a {@code @Scheduled} task. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @ConditionalOnBackgroundWork
    static class ScheduleConfiguration {
        @Bean
        ScheduledTaskRegistrar gitMirrorRetentionTasks(GitMirrorRetention retention) {
            var tasks = new ScheduledTaskRegistrar();
            tasks.addFixedDelayTask(new FixedDelayTask(
                    retention::removeUnretainedMirrors, Duration.ofMinutes(1), Duration.ofMinutes(1)));
            tasks.addFixedDelayTask(new FixedDelayTask(
                    retention::evictLeastRecentlyUsedMirrors, Duration.ofMinutes(1), Duration.ofMinutes(1)));
            return tasks;
        }
    }
}
