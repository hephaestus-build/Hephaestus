package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallClass;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The meters of each pool, registered for every class and reason at once, so a rate or an absence alert starts from
 * zero. Labels are {@code pool}, {@code class} and {@code reason} only, each a closed set; never a workspace, model, job
 * or turn.
 */
final class LlmPoolMeters {

    /** Why a call waited: {@code none} when its pool admitted it at once. */
    static final List<String> WAIT_REASONS = List.of("none", "capacity", "cooldown");

    /** Why a worker cancelled a call: it could not renew the permit, or the database no longer had it. */
    static final List<String> CANCEL_REASONS = List.of("no_renewal", "lease_lost");

    private final MeterRegistry registry;
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final Counter renewalFailures;

    LlmPoolMeters(MeterRegistry registry) {
        this.registry = registry;
        this.renewalFailures = Counter.builder(AgentMetrics.LLM_POOL_LEASE_RENEWAL_FAILURES)
                .description("Failed renewals of a worker's model call permits")
                .register(registry);
    }

    Pool pool(String slug) {
        return pools.computeIfAbsent(slug, this::register);
    }

    void renewalFailed() {
        renewalFailures.increment();
    }

    private Pool register(String slug) {
        Map<LlmCallClass, AtomicInteger> active = new EnumMap<>(LlmCallClass.class);
        for (LlmCallClass callClass : LlmCallClass.values()) {
            AtomicInteger count = new AtomicInteger();
            Gauge.builder(AgentMetrics.LLM_POOL_PERMITS_ACTIVE, count, AtomicInteger::get)
                    .description("Permits this worker holds")
                    .tag("pool", slug)
                    .tag("class", callClass.tag())
                    .strongReference(true)
                    .register(registry);
            active.put(callClass, count);
            for (String reason : WAIT_REASONS) {
                waitTimer(slug, callClass, reason);
                if (!reason.equals("none")) abandonedCounter(slug, callClass, reason);
            }
        }
        for (String reason : CANCEL_REASONS) cancelledCounter(slug, reason);
        AtomicLong coolingUntil = new AtomicLong(System.nanoTime());
        Gauge.builder(
                        AgentMetrics.LLM_POOL_COOLDOWN_ACTIVE,
                        coolingUntil,
                        until -> System.nanoTime() - until.get() < 0 ? 1 : 0)
                .description("1 while this worker knows its pool to be in a cooldown")
                .tag("pool", slug)
                .strongReference(true)
                .register(registry);
        Counter.builder(AgentMetrics.LLM_POOL_LEASE_RECLAIMED)
                .description("Permits freed because their worker stopped renewing them")
                .tag("pool", slug)
                .register(registry);
        Counter.builder(AgentMetrics.LLM_POOL_UPSTREAM_THROTTLED)
                .description("Rate limit answers from a pool's provider")
                .tag("pool", slug)
                .register(registry);
        return new Pool(slug, active, coolingUntil);
    }

    private Timer waitTimer(String slug, LlmCallClass callClass, String reason) {
        return Timer.builder(AgentMetrics.LLM_POOL_PERMIT_WAIT)
                .description("How long a model call waited for its pool")
                .tag("pool", slug)
                .tag("class", callClass.tag())
                .tag("reason", reason)
                .publishPercentileHistogram()
                .register(registry);
    }

    private Counter abandonedCounter(String slug, LlmCallClass callClass, String reason) {
        return Counter.builder(AgentMetrics.LLM_POOL_PERMIT_ABANDONED)
                .description("Model calls that ended while they waited for their pool")
                .tag("pool", slug)
                .tag("class", callClass.tag())
                .tag("reason", reason)
                .register(registry);
    }

    private Counter cancelledCounter(String slug, String reason) {
        return Counter.builder(AgentMetrics.LLM_POOL_SELF_FENCE_CANCELLED)
                .description("Model calls a worker cancelled because it no longer held their permit")
                .tag("pool", slug)
                .tag("reason", reason)
                .register(registry);
    }

    /** The meters of one pool. */
    final class Pool {
        private final String slug;
        private final Map<LlmCallClass, AtomicInteger> active;
        private final AtomicLong coolingUntil;

        private Pool(String slug, Map<LlmCallClass, AtomicInteger> active, AtomicLong coolingUntil) {
            this.slug = slug;
            this.active = active;
            this.coolingUntil = coolingUntil;
        }

        AtomicInteger active(LlmCallClass callClass) {
            return Objects.requireNonNull(active.get(callClass));
        }

        void waited(LlmCallClass callClass, String reason, long nanos) {
            waitTimer(slug, callClass, reason).record(nanos, TimeUnit.NANOSECONDS);
        }

        void abandoned(LlmCallClass callClass, String reason) {
            abandonedCounter(slug, callClass, reason).increment();
        }

        void cancelled(String reason) {
            cancelledCounter(slug, reason).increment();
        }

        void reclaimed(int permits) {
            Counter.builder(AgentMetrics.LLM_POOL_LEASE_RECLAIMED)
                    .tag("pool", slug)
                    .register(registry)
                    .increment(permits);
        }

        void throttled() {
            Counter.builder(AgentMetrics.LLM_POOL_UPSTREAM_THROTTLED)
                    .tag("pool", slug)
                    .register(registry)
                    .increment();
        }

        /** Records that the pool is in a cooldown for {@code millis} from now, as this worker knows it. */
        void coolingFor(long millis) {
            long until = System.nanoTime() + millis * 1_000_000;
            coolingUntil.accumulateAndGet(until, (current, next) -> current - next > 0 ? current : next);
        }
    }
}
