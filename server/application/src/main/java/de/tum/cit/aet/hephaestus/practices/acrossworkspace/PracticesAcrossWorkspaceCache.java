package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What every reader of a workspace's page shares, counted once: the splits and the tiles of each window, never a
 * reader's own figures.
 *
 * <ul>
 *   <li>A workspace counted while another request counts it waits for that count rather than starting its own, so
 *       a course opening the page at once runs one count, not one per student.
 *   <li>New review results keep the count readable and count the workspace again in the background a moment later,
 *       once for a burst of reviews, so no reader waits for it. A count older than {@link #FRESH} is counted again
 *       the same way when it is read.
 *   <li>A changed AI choice, a hidden member, a hidden repository or an invalidated observation drops the count:
 *       no reader reads a count that still holds someone the workspace may no longer count.
 *   <li>Nothing is read that is older than {@link #EXPIRES}.
 * </ul>
 */
@Component
class PracticesAcrossWorkspaceCache implements DisposableBean {

    /** How long a count is read before a read counts again in the background. */
    static final Duration FRESH = Duration.ofMinutes(2);

    /** How long a count may be read at all. */
    static final Duration EXPIRES = Duration.ofMinutes(10);

    /** How long new review results wait to be counted, so a burst of reviews is counted once. */
    static final Duration RECOUNT_DELAY = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(PracticesAcrossWorkspaceCache.class);

    private static final long MAX_ENTRIES = 2_000;

    private final AsyncCache<Key, Counted> counts;
    private final ConcurrentMap<Long, AtomicLong> generations = new ConcurrentHashMap<>();
    private final Set<Long> recountsDue = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService recounts;
    private final Clock clock;
    private final Duration fresh;
    private final Duration recountDelay;

    @Autowired
    PracticesAcrossWorkspaceCache(MeterRegistry meterRegistry, Clock clock) {
        this(meterRegistry, clock, FRESH, EXPIRES, RECOUNT_DELAY);
    }

    PracticesAcrossWorkspaceCache(
            MeterRegistry meterRegistry, Clock clock, Duration fresh, Duration expires, Duration recountDelay) {
        this.counts = Caffeine.newBuilder()
                .expireAfterWrite(expires)
                .maximumSize(MAX_ENTRIES)
                .recordStats()
                .buildAsync();
        CaffeineCacheMetrics.monitor(meterRegistry, counts, "practices_across_workspace", List.of());
        this.recounts = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "practices-across-workspace-recount");
            thread.setDaemon(true);
            return thread;
        });
        this.clock = clock;
        this.fresh = fresh;
        this.recountDelay = recountDelay;
    }

    /**
     * One view of the workspace: the one counted, or the one another request is counting right now, or a new count
     * on this thread. A count that fails is not kept, so the next reader counts again.
     */
    @SuppressWarnings("unchecked") // Every view is stored under a key naming its type.
    <T> T get(long workspaceId, String view, Supplier<T> count) {
        Key key = new Key(workspaceId, generation(workspaceId).get(), view);
        CompletableFuture<Counted> mine = new CompletableFuture<>();
        CompletableFuture<Counted> theirs = counts.asMap().putIfAbsent(key, mine);
        if (theirs == null) {
            try {
                T counted = count.get();
                mine.complete(new Counted(counted, clock.instant(), count));
                return counted;
            } catch (RuntimeException | Error failure) {
                counts.asMap().remove(key, mine);
                mine.completeExceptionally(failure);
                throw failure;
            }
        }
        Counted counted;
        try {
            counted = theirs.join();
        } catch (CompletionException failure) {
            // The request that counted failed; this one counts for itself rather than failing too.
            return count.get();
        }
        if (counted.at().plus(fresh).isBefore(clock.instant())) {
            recountLater(workspaceId, Duration.ZERO);
        }
        return (T) counted.value();
    }

    /** New review results: the counts stay readable and are counted again in the background shortly. */
    void recountLater(long workspaceId) {
        recountLater(workspaceId, recountDelay);
    }

    /** Drops every count of the workspace, including one still being counted, which is then not kept. */
    void invalidate(long workspaceId) {
        generation(workspaceId).incrementAndGet();
        counts.asMap().keySet().removeIf(key -> key.workspaceId() == workspaceId);
    }

    /** Drops every count of every workspace. */
    void invalidateAll() {
        generations.values().forEach(AtomicLong::incrementAndGet);
        counts.synchronous().invalidateAll();
    }

    /**
     * Counts every view of the workspace that is kept again, on this thread, and keeps each new count unless the
     * workspace's counts were dropped meanwhile. A view whose count fails keeps the count it had.
     */
    void recountNow(long workspaceId) {
        long generation = generation(workspaceId).get();
        for (Map.Entry<Key, CompletableFuture<Counted>> entry : counts.asMap().entrySet()) {
            Key key = entry.getKey();
            CompletableFuture<Counted> kept = entry.getValue();
            if (key.workspaceId() != workspaceId
                    || key.generation() != generation
                    || !kept.isDone()
                    || kept.isCompletedExceptionally()) {
                continue;
            }
            Supplier<?> count = kept.join().count();
            try {
                Counted recounted = new Counted(count.get(), clock.instant(), count);
                if (generation(workspaceId).get() == generation) {
                    counts.asMap().replace(key, kept, CompletableFuture.completedFuture(recounted));
                }
            } catch (RuntimeException failure) {
                log.warn(
                        "Kept the previous count of practices across the workspace: workspaceId={}, view={}",
                        workspaceId,
                        key.view(),
                        failure);
            }
        }
    }

    @Override
    public void destroy() {
        recounts.shutdownNow();
    }

    private void recountLater(long workspaceId, Duration delay) {
        if (!recountsDue.add(workspaceId)) {
            return;
        }
        try {
            // A recount logs its own failures; its future carries nothing to wait for.
            var unused = recounts.schedule(
                    () -> {
                        recountsDue.remove(workspaceId);
                        recountNow(workspaceId);
                    },
                    delay.toMillis(),
                    TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            recountsDue.remove(workspaceId);
            throw rejected;
        }
    }

    private AtomicLong generation(long workspaceId) {
        return generations.computeIfAbsent(workspaceId, ignored -> new AtomicLong());
    }

    private record Key(long workspaceId, long generation, String view) {}

    /** One view's count, when it was counted, and how to count it again. */
    private record Counted(Object value, Instant at, Supplier<?> count) {}
}
