package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.RunAdmission;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmCallPools;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentProgress;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Whether a model pool takes another measuring review now. A pool with no running measurement always takes one. Past
 * that, it takes the next one only while the open practices of its running measurements and of the next one fit the
 * calls that the pool lets measurements hold. Each open practice is a session with one call in flight. The pool also
 * waits while a measuring call waits longer than the permit aging and until its newest measurement has run that long.
 *
 * <p>The dispatch asks this in the claim's order, so the workspace with the fewest leased runs goes first, and a held
 * review gets no sandbox. The claim asks it again under the pool's lock, so two servers never admit past the rule.
 */
@Component
@ConditionalOnAgentRuntime
public class ReviewPoolAdmission implements RunAdmission {
    private final ReviewJobRepository jobs;
    private final LlmCallPools pools;
    private final ReviewAssignmentProgress progress;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> held = new ConcurrentHashMap<>();

    public ReviewPoolAdmission(
            ReviewJobRepository jobs, LlmCallPools pools, ReviewAssignmentProgress progress, MeterRegistry meters) {
        this.jobs = jobs;
        this.pools = pools;
        this.progress = progress;
        this.meters = meters;
    }

    @Override
    public Pass pass(Set<AgentRunKind> kinds) {
        return new ReviewPass(kinds);
    }

    /** One dispatch pass: what it has read, and the place it gave or held for each pool. */
    private final class ReviewPass implements Pass {
        private final Set<AgentRunKind> kinds;
        private final Map<Long, Boolean> admitting = new HashMap<>();
        private final Map<Long, Long> holding = new HashMap<>();
        private final Map<UUID, @Nullable Long> poolOfRun = new HashMap<>();
        private final Map<UUID, Integer> practicesOfRun = new HashMap<>();
        private final Map<LlmModelResolver.ConnectionRef, Long> poolOfModel = new HashMap<>();

        ReviewPass(Set<AgentRunKind> kinds) {
            this.kinds = kinds;
            if (!kinds.contains(AgentRunKind.REVIEW)) return;
            // A review whose sandbox started but has not claimed its run yet already took its pool's place.
            for (var model : jobs.findStartingModels()) {
                Long pool = poolOf(model);
                if (pool != null) admitting.put(pool, false);
            }
        }

        @Override
        public void read(List<AgentRuns.DueRun> page) {
            List<UUID> unread = page.stream()
                    .filter(due -> due.kind() == AgentRunKind.REVIEW)
                    .map(due -> due.run().id())
                    .filter(run -> !poolOfRun.containsKey(run))
                    .toList();
            if (unread.isEmpty()) return;
            unread.forEach(run -> poolOfRun.put(run, null));
            var queued = jobs.findQueuedModels(unread);
            var ended = progress.endedPracticeCounts(queued.stream()
                    .map(ReviewJobRepository.QueuedModel::getJobId)
                    .toList());
            for (var model : queued) {
                poolOfRun.put(model.getRunId(), poolOf(model));
                practicesOfRun.put(
                        model.getRunId(), remaining(model.getPractices(), ended.getOrDefault(model.getJobId(), 0)));
            }
        }

        @Override
        public boolean admits(AgentRuns.DueRun due) {
            if (due.kind() != AgentRunKind.REVIEW) return true;
            if (!poolOfRun.containsKey(due.run().id())) read(List.of(due));
            Long pool = poolOfRun.get(due.run().id());
            // The claim refuses an unreadable snapshot or an unavailable model with its own reason.
            if (pool == null) return true;
            // One admission for each pool in a pass: the next one waits until the first shows its load.
            int practices = practicesOfRun.getOrDefault(due.run().id(), 1);
            boolean admitted = admitting.computeIfAbsent(pool, key -> ReviewPoolAdmission.this.admits(key, practices));
            if (admitted) admitting.put(pool, false);
            else holding.merge(pool, 1L, Long::sum);
            return admitted;
        }

        @Override
        public void ended() {
            if (!kinds.contains(AgentRunKind.REVIEW)) return;
            // The pools of workspace models share one label, so their counts add up.
            Map<String, Long> labelled = new HashMap<>();
            holding.forEach((pool, count) -> labelled.merge(pools.poolLabel(pool), count, Long::sum));
            held.forEach((label, gauge) -> gauge.set(labelled.getOrDefault(label, 0L)));
            labelled.forEach((label, count) -> gauge(label).set(count));
        }

        private @Nullable Long poolOf(ReviewJobRepository.QueuedModel frozen) {
            if (!Objects.equals(frozen.getSchemaVersion(), ConfigSnapshot.SCHEMA_VERSION)) return null;
            try {
                String scope = frozen.getConnectionScope();
                var model = new LlmModelResolver.ConnectionRef(
                        scope == null ? null : UsageFundingSource.valueOf(scope),
                        frozen.getConnectionId(),
                        frozen.getModelId(),
                        frozen.getWorkspaceId());
                Long known = poolOfModel.get(model);
                if (known != null) return known;
                long pool = pools.poolFor(model);
                poolOfModel.put(model, pool);
                return pool;
            } catch (RuntimeException unreadable) {
                return null;
            }
        }
    }

    record Claim(long poolId, boolean admitted) {}

    /** Rechecks dispatch's decision while holding the pool's admission lock. */
    Claim claim(LlmModelResolver.ConnectionRef model, ReviewJob job) {
        long poolId = pools.poolFor(model);
        pools.lockAdmission(poolId);
        var load = jobs.poolLoad(poolId);
        if (load.getRunning() == 0) return new Claim(poolId, true);
        var jobIds = new ArrayList<>(List.of(load.getJobIds()));
        jobIds.add(job.getId());
        var ended = progress.endedPracticeCounts(jobIds);
        long practices = openWidth(load.getPractices() + originalWidth(job), ended);
        return new Claim(poolId, admitsWhileBusy(poolId, load, practices));
    }

    String poolLabel(long poolId) {
        return pools.poolLabel(poolId);
    }

    /** The same rule serves dispatch and the claim that holds the pool lock. */
    private boolean admits(long poolId, int practices) {
        var load = jobs.poolLoad(poolId);
        if (load.getRunning() == 0) return true;
        var ended = progress.endedPracticeCounts(List.of(load.getJobIds()));
        return admitsWhileBusy(poolId, load, openWidth(load.getPractices(), ended) + practices);
    }

    private boolean admitsWhileBusy(long poolId, ReviewJobRepository.PoolLoad load, long practices) {
        if (practices > room(load)) return false;
        Instant newest = load.getNewestStart();
        return !pools.congested(poolId)
                && (newest == null || newest.plus(pools.admissionSpacing()).isBefore(Instant.now()));
    }

    private static int remaining(int practices, int ended) {
        return Math.max(0, practices - ended);
    }

    private static int originalWidth(ReviewJob job) {
        return Math.max(1, job.getInput().assignments().size());
    }

    private static long openWidth(long practices, Map<UUID, Integer> ended) {
        return Math.max(0, practices - ended.values().stream().mapToLong(Integer::longValue).sum());
    }

    /**
     * The calls that measurements may hold in the pool now: its learned limit, capped by its maximum, less the calls kept
     * for interactive work. A pool that is gone holds nothing back: the claim refuses the review with its own reason.
     */
    static int room(ReviewJobRepository.PoolLoad load) {
        Integer learned = load.getLearnedLimit();
        if (learned == null) return Integer.MAX_VALUE;
        int limit = LlmCallLimit.effective(learned, load.getMaxConcurrentCalls());
        return limit - LlmCallLimit.reserve(limit);
    }

    /**
     * The practice sessions that a running measurement of {@code practices} practices may run at once: the pool's room
     * less the practices of the other running measurements, and at least one, so a review always makes progress.
     */
    int sessionLimit(long poolId, ReviewJob job) {
        var load = jobs.poolLoad(poolId);
        var ended = progress.endedPracticeCounts(List.of(load.getJobIds()));
        int own = remaining(originalWidth(job), ended.getOrDefault(job.getId(), 0));
        return sessionLimit(load, openWidth(load.getPractices(), ended), own);
    }

    static int sessionLimit(ReviewJobRepository.PoolLoad load, long reserved, int practices) {
        long others = Math.max(0, reserved - practices);
        long free = (long) room(load) - others;
        return (int) Math.max(1, Math.min(practices, free));
    }

    private AtomicLong gauge(String label) {
        return held.computeIfAbsent(label, key -> {
            var count = new AtomicLong();
            meters.gauge(AgentMetrics.AGENT_REVIEW_POOL_HELD, List.of(Tag.of("pool", key)), count);
            return count;
        });
    }
}
