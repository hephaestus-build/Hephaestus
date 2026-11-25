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
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.Instant;
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
 * that, it takes the next one only while the practices of its running measurements and of the next one fit the calls
 * that the pool lets measurements hold: each practice is a session with one call in flight, so a review counts as wide
 * as its practices, not as one. It also waits while a measuring call waits longer than the permit aging, and until its
 * newest measurement has run that long. Reviews admitted past a pool's capacity slow down together and all run out of
 * time, while a waiting review keeps its whole budget.
 *
 * <p>The dispatch asks this in the claim's order, so the workspace with the fewest leased runs goes first, and a held
 * review gets no sandbox. The claim asks it again under the pool's lock, so two servers never admit past the rule.
 */
@Component
@ConditionalOnAgentRuntime
public class ReviewPoolAdmission implements RunAdmission {
    private final ReviewJobRepository jobs;
    private final LlmCallPools pools;
    private final MeterRegistry meters;
    private final Map<String, AtomicLong> held = new ConcurrentHashMap<>();

    public ReviewPoolAdmission(ReviewJobRepository jobs, LlmCallPools pools, MeterRegistry meters) {
        this.jobs = jobs;
        this.pools = pools;
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
            for (var model : jobs.findQueuedModels(unread)) {
                poolOfRun.put(model.getRunId(), poolOf(model));
                practicesOfRun.put(model.getRunId(), model.getPractices());
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

    /** Whether the pool takes another measurement of {@code practices} practices now. */
    public boolean admits(long poolId, int practices) {
        return admits(jobs.poolLoad(poolId), pools, poolId, practices);
    }

    /** The rule, which the claim also applies while it holds the pool's lock. */
    static boolean admits(ReviewJobRepository.PoolLoad load, LlmCallPools pools, long poolId, int practices) {
        if (load.getRunning() == 0) return true;
        Integer learned = load.getLearnedLimit();
        // A pool that is gone holds nothing: the claim refuses the review with its own reason.
        if (learned == null) return true;
        int limit = LlmCallLimit.effective(learned, load.getMaxConcurrentCalls());
        // Measurements never hold the calls kept for interactive work.
        if (load.getPractices() + practices > limit - LlmCallLimit.reserve(limit)) return false;
        Instant newest = load.getNewestStart();
        return !pools.congested(poolId)
                && (newest == null || newest.plus(pools.admissionSpacing()).isBefore(Instant.now()));
    }

    private AtomicLong gauge(String label) {
        return held.computeIfAbsent(label, key -> {
            var count = new AtomicLong();
            meters.gauge(AgentMetrics.AGENT_REVIEW_POOL_HELD, List.of(Tag.of("pool", key)), count);
            return count;
        });
    }
}
