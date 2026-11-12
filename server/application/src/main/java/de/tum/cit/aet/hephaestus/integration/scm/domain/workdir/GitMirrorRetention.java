package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.metrics.ScmMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Removes the mirrors under this process's root that their workspace no longer keeps. Each server holds its own
 * root, so each one reconciles its own; a server that was offline catches up on its next pass. A
 * workspace keeps a mirror while it monitors the repository or still indexes it, as a stopped GitLab project is kept
 * until its work is removed. Repository removal and workspace purge therefore reach every role's mirrors.
 *
 * <p>A kept mirror is still a cache. When the volume nears its size, the mirrors that a capture used longest ago go too,
 * because the kubelet would otherwise evict the whole Pod with its running attempts, and the next Pod would fill the same
 * mirrors again. Its next capture fetches it again, as after a restart.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Walks every workspace's mirrors; each check filters on the mirror's own workspace")
public class GitMirrorRetention {
    private static final Logger log = LoggerFactory.getLogger(GitMirrorRetention.class);

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
    private final RepositoryRepository repositories;
    private final long maxCacheBytes;
    private final AtomicLong cacheBytes = new AtomicLong();
    private final Counter evictions;

    public GitMirrorRetention(
            GitRepositoryManager mirrors,
            RepositoryRepository repositories,
            GitRepositoryProperties properties,
            MeterRegistry meterRegistry) {
        this.mirrors = mirrors;
        this.repositories = repositories;
        this.maxCacheBytes = properties.maxCacheBytes();
        Gauge.builder(ScmMetrics.GIT_CACHE_BYTES, cacheBytes, AtomicLong::get)
                .description("Disk used on the volume of Git mirrors, review checkouts and staged archives")
                .baseUnit("bytes")
                .register(meterRegistry);
        Gauge.builder(ScmMetrics.GIT_CACHE_LIMIT_BYTES, () -> maxCacheBytes)
                .description("Size of the volume of Git mirrors, review checkouts and staged archives")
                .baseUnit("bytes")
                .register(meterRegistry);
        this.evictions = Counter.builder(ScmMetrics.GIT_MIRROR_EVICTIONS)
                .description("Kept Git mirrors deleted because their volume neared its size")
                .register(meterRegistry);
    }

    /** @return how many mirrors were deleted */
    public int removeUnretainedMirrors() {
        if (!mirrors.isEnabled()) return 0;
        int removed = 0;
        for (RepositoryKey repository : mirrors.localRepositories()) {
            try {
                // Most mirrors are kept; check before waiting for a mirror lock that a long fetch may hold.
                if (isRetained(repository)) continue;
                if (mirrors.deleteUnretainedRepository(repository, LOCK_WAIT, () -> isRetained(repository))) removed++;
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
        long used = mirrors.cacheBytes();
        long allowed = (long) (maxCacheBytes * EVICTION_SHARE);
        cacheBytes.set(used);
        Instant inUseSince = Instant.now().minus(IN_USE_FOR);
        int evicted = 0;
        if (used > allowed) {
            for (RepositoryKey repository : mirrors.localRepositoriesLeastRecentlyUsedFirst()) {
                if (used <= allowed || mirrors.lastUsed(repository).isAfter(inUseSince)) break;
                long size;
                try {
                    size = mirrors.mirrorBytes(repository);
                    // Checked again under the lock: a capture's fetch that this pass waited for has just used the
                    // mirror.
                    if (!mirrors.deleteUnretainedRepository(
                            repository,
                            LOCK_WAIT,
                            () -> mirrors.lastUsed(repository).isAfter(inUseSince))) continue;
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

    /** Its own scheduler thread: a sweep must not wait on a {@code @Scheduled} task. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnServerRole
    @Profile("!test & !specs & !cds-training")
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
