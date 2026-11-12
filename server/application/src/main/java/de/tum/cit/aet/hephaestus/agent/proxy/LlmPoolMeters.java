package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunBand;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

/**
 * The meters of each pool, registered for every band and reason at once, so a rate or an absence alert starts from
 * zero. Labels are {@code pool}, {@code band}, {@code reason}, {@code status} and {@code cause} only; never a workspace,
 * job or turn. An instance model's own pool is labelled by that model. All pools that workspaces' own models have to
 * themselves share one label, as there can be many of them and their ids are no one else's concern.
 */
final class LlmPoolMeters {

    /** The own pool of a workspace's model starts with this, in the database's trigger. */
    private static final String WORKSPACE_MODEL_POOL = "own-workspace-model-";

    /** The label of those pools. */
    static final String WORKSPACE_MODELS_LABEL = "workspace-models";

    /** The answers that lower a pool's limit: {@link LlmCallPermits#throttles}. */
    static final List<Integer> THROTTLE_STATUSES = List.of(429, 503, 529);

    /**
     * The GenAI semantic conventions' buckets for time to first chunk, 0.01 s doubling to 81.92 s, and two more, as a
     * provider's queue can hold a first token for minutes.
     */
    private static final Duration[] FIRST_CHUNK_BUCKETS = IntStream.rangeClosed(0, 15)
            .mapToObj(doubling -> Duration.ofMillis(10L << doubling))
            .toArray(Duration[]::new);

    /** What lowered a limit. */
    static final List<String> DECREASE_CAUSES = List.of("refusal", "delay", "unanswered");

    /** Why a call waited: {@code none} when its pool admitted it at once. */
    static final List<String> WAIT_REASONS = List.of("none", "capacity", "cooldown");

    /**
     * How a call that reached its provider ended. {@code refused}, {@code length} and {@code completed} are the
     * provider's own stop reason ({@link ModelStopReason}); {@code unanswered} is a served stream without content.
     * The proxy's own refusals never reach a provider: {@code llm_proxy_refused_total} counts them.
     */
    static final List<String> CALL_OUTCOMES =
            List.of("completed", "length", "refused", "unanswered", "error", "stalled", "cancelled");

    /** A conversation's call should wait for seconds at most; a review's call can wait for minutes. */
    private static final Duration[] WAIT_BOUNDS = {
        Duration.ofMillis(100),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(30),
        Duration.ofMinutes(1),
        Duration.ofMinutes(2),
        Duration.ofMinutes(5),
        Duration.ofMinutes(10),
        Duration.ofMinutes(30)
    };

    /** The outcomes that the provider stated, which a stream without content keeps instead of {@code unanswered}. */
    static final Set<String> EXPLICIT_OUTCOMES = Set.of("refused", "length", "error");

    /** The token buckets that a provider bills apart. */
    static final List<String> TOKEN_TYPES = List.of("input", "output", "cache_read", "cache_write");

    /**
     * Why a server cancelled a call: it could not renew the permit, the database no longer had it, the attempt ended, or
     * the call outlived its hard end.
     */
    static final List<String> CANCEL_REASONS = List.of("no_renewal", "lease_lost", "attempt_ended", "past_deadline");

    /** The {@code band} label of a call: its band in lower case. */
    static String tag(AgentRunBand band) {
        return band.name().toLowerCase(Locale.ROOT);
    }

    private final MeterRegistry registry;
    private final Map<String, Pool> pools = new ConcurrentHashMap<>();
    private final Counter renewalFailures;

    LlmPoolMeters(MeterRegistry registry) {
        this.registry = registry;
        this.renewalFailures = Counter.builder(AgentMetrics.LLM_POOL_LEASE_RENEWAL_FAILURES)
                .description("Failed renewals of a server's model call permits")
                .register(registry);
    }

    Pool pool(String slug) {
        return pools.computeIfAbsent(label(slug), this::register);
    }

    private static String label(String slug) {
        return slug.startsWith(WORKSPACE_MODEL_POOL) ? WORKSPACE_MODELS_LABEL : slug;
    }

    /** Sets the gauges from what the database says. A pool without a row here has no one waiting. */
    void observe(List<LlmCallPermitRepository.Backlog> backlog) {
        Map<String, Backlog> sums = new HashMap<>();
        for (var row : backlog) {
            Backlog sum = sums.computeIfAbsent(label(row.getSlug()), key -> new Backlog());
            Integer baseline = row.getBaselineFirstTokenMs();
            if (baseline != null) sum.baselineMillis = Math.max(sum.baselineMillis, baseline);
            sum.limit += LlmCallLimit.effective(row.getLearnedLimit(), row.getMaxConcurrentCalls());
            sum.waiting.merge(AgentRunBand.INTERACTIVE, row.getWaitingInteractive(), Long::sum);
            sum.waiting.merge(AgentRunBand.BATCH, row.getWaitingBatch(), Long::sum);
            sum.waiting.merge(AgentRunBand.BACKGROUND, row.getWaitingBackground(), Long::sum);
        }
        sums.keySet().forEach(this::pool);
        pools.forEach((label, pool) -> {
            Backlog sum = sums.get(label);
            if (sum != null) {
                pool.limit.set(sum.limit);
                pool.baselineMillis.set(sum.baselineMillis);
            }
            pool.waiting.forEach((band, waiting) -> waiting.set(sum == null ? 0 : sum.waiting.getOrDefault(band, 0L)));
        });
    }

    /** What the pools of one label stand at. */
    private static final class Backlog {
        private long limit;
        private long baselineMillis;
        private final Map<AgentRunBand, Long> waiting = new EnumMap<>(AgentRunBand.class);
    }

    void renewalFailed() {
        renewalFailures.increment();
    }

    private Pool register(String slug) {
        Map<AgentRunBand, AtomicInteger> active = new EnumMap<>(AgentRunBand.class);
        Map<AgentRunBand, AtomicLong> waiting = new EnumMap<>(AgentRunBand.class);
        for (AgentRunBand band : AgentRunBand.values()) {
            AtomicInteger count = new AtomicInteger();
            Gauge.builder(AgentMetrics.LLM_POOL_PERMITS_ACTIVE, count, AtomicInteger::get)
                    .description("Permits this server holds")
                    .tag("pool", slug)
                    .tag("band", tag(band))
                    .strongReference(true)
                    .register(registry);
            active.put(band, count);
            waiting.put(
                    band,
                    poolGauge(AgentMetrics.LLM_POOL_WAITERS, "Live model calls that wait for a pool", slug, band));
            grantedCounter(slug, band);
            for (String reason : WAIT_REASONS) {
                waitTimer(slug, band, reason);
                if (!reason.equals("none")) abandonedCounter(slug, band, reason);
            }
        }
        for (String reason : CANCEL_REASONS) cancelledCounter(slug, reason);
        AtomicLong coolingUntil = new AtomicLong(System.nanoTime());
        Gauge.builder(
                        AgentMetrics.LLM_POOL_COOLDOWN_ACTIVE,
                        coolingUntil,
                        until -> System.nanoTime() - until.get() < 0 ? 1 : 0)
                .description("1 while this server knows its pool to be in a cooldown")
                .tag("pool", slug)
                .strongReference(true)
                .register(registry);

        for (int status : THROTTLE_STATUSES) throttledCounter(slug, status);
        for (String cause : DECREASE_CAUSES) limitDecreasesCounter(slug, cause);
        absorbedCounter(slug);
        expiredCounter(slug);
        for (String bucket : RateLimitHeaders.TAGS) bucketReachedCounter(slug, bucket);
        for (AgentRunBand band : AgentRunBand.values()) {
            firstTokenTimer(slug, band);
            operationTimer(slug, band);
            for (String outcome : CALL_OUTCOMES) callsCounter(slug, band, outcome);
            for (String type : TOKEN_TYPES) tokensCounter(slug, band, type);
        }
        baselineResetsCounter(slug);
        return new Pool(
                slug,
                active,
                coolingUntil,
                waiting,
                poolGauge(AgentMetrics.LLM_POOL_LIMIT, "The calls in flight a pool allows now", slug, null),
                poolGauge(
                        AgentMetrics.LLM_POOL_FIRST_TOKEN_BASELINE,
                        "The fastest first token known from a pool's provider, in milliseconds",
                        slug,
                        null));
    }

    private AtomicLong poolGauge(String name, String description, String slug, @Nullable AgentRunBand band) {
        AtomicLong value = new AtomicLong();
        var gauge = Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .tag("pool", slug)
                .strongReference(true);
        if (band != null) gauge.tag("band", tag(band));
        gauge.register(registry);
        return value;
    }

    private Counter throttledCounter(String slug, int status) {
        return Counter.builder(AgentMetrics.LLM_POOL_UPSTREAM_THROTTLED)
                .description("Answers of a pool's provider that it serves too many calls")
                .tag("pool", slug)
                .tag("status", Integer.toString(status))
                .register(registry);
    }

    private Counter limitDecreasesCounter(String slug, String cause) {
        return Counter.builder(AgentMetrics.LLM_POOL_LIMIT_DECREASES)
                .description("How often a provider's refusal, queue or unanswered stream lowered a pool's limit")
                .tag("pool", slug)
                .tag("cause", cause)
                .register(registry);
    }

    private Timer firstTokenTimer(String slug, AgentRunBand band) {
        return Timer.builder(AgentMetrics.GEN_AI_CLIENT_TIME_TO_FIRST_CHUNK)
                .description("How long a served stream waited for its provider's first event, after its pool's grant")
                .tag("pool", slug)
                .tag("band", tag(band))
                .serviceLevelObjectives(FIRST_CHUNK_BUCKETS)
                .register(registry);
    }

    private Timer operationTimer(String slug, AgentRunBand band) {
        return Timer.builder(AgentMetrics.GEN_AI_CLIENT_OPERATION_DURATION)
                .description("How long a model call took from its pool's grant to its end")
                .tag("pool", slug)
                .tag("band", tag(band))
                .serviceLevelObjectives(FIRST_CHUNK_BUCKETS)
                .register(registry);
    }

    private Counter callsCounter(String slug, AgentRunBand band, String outcome) {
        return Counter.builder(AgentMetrics.GEN_AI_CLIENT_CALLS)
                .description("Model calls that reached their provider, by how they ended")
                .tag("pool", slug)
                .tag("band", tag(band))
                .tag("outcome", outcome)
                .register(registry);
    }

    private Counter tokensCounter(String slug, AgentRunBand band, String type) {
        return Counter.builder(AgentMetrics.GEN_AI_CLIENT_TOKENS)
                .description("Tokens that providers reported for model calls")
                .tag("pool", slug)
                .tag("band", tag(band))
                .tag("type", type)
                .register(registry);
    }

    private Counter baselineResetsCounter(String slug) {
        return Counter.builder(AgentMetrics.LLM_POOL_FIRST_TOKEN_BASELINE_RESETS)
                .description("How often a pool at its floor took a new first-token baseline")
                .tag("pool", slug)
                .register(registry);
    }

    private Counter bucketReachedCounter(String slug, String bucket) {
        return Counter.builder(AgentMetrics.LLM_POOL_UPSTREAM_BUCKET_REACHED)
                .description("Served answers whose rate limit headers showed an empty bucket")
                .tag("pool", slug)
                .tag("bucket", bucket)
                .register(registry);
    }

    private Counter absorbedCounter(String slug) {
        return Counter.builder(AgentMetrics.LLM_POOL_UPSTREAM_ABSORBED)
                .description("Provider refusals a caller never saw, because its call waited and asked again")
                .tag("pool", slug)
                .register(registry);
    }

    private Counter grantedCounter(String slug, AgentRunBand band) {
        return Counter.builder(AgentMetrics.LLM_POOL_PERMITS_GRANTED)
                .description("Model calls that received a grant of their pool")
                .tag("pool", slug)
                .tag("band", tag(band))
                .register(registry);
    }

    private Timer waitTimer(String slug, AgentRunBand band, String reason) {
        return Timer.builder(AgentMetrics.LLM_POOL_PERMIT_WAIT)
                .description("How long a model call waited for its pool")
                .tag("pool", slug)
                .tag("band", tag(band))
                .tag("reason", reason)
                .serviceLevelObjectives(WAIT_BOUNDS)
                .register(registry);
    }

    private Counter expiredCounter(String slug) {
        return Counter.builder(AgentMetrics.LLM_POOL_PERMITS_EXPIRED)
                .description("Permits whose server stopped renewing them while their call was in flight")
                .tag("pool", slug)
                .register(registry);
    }

    private Counter abandonedCounter(String slug, AgentRunBand band, String reason) {
        return Counter.builder(AgentMetrics.LLM_POOL_PERMIT_ABANDONED)
                .description("Model calls that ended while they waited for their pool")
                .tag("pool", slug)
                .tag("band", tag(band))
                .tag("reason", reason)
                .register(registry);
    }

    private Counter cancelledCounter(String slug, String reason) {
        return Counter.builder(AgentMetrics.LLM_POOL_SELF_FENCE_CANCELLED)
                .description("Model calls a server cancelled because it no longer held their permit")
                .tag("pool", slug)
                .tag("reason", reason)
                .register(registry);
    }

    /** The meters of one pool. */
    final class Pool {
        private final String slug;
        private final Map<AgentRunBand, AtomicInteger> active;
        private final AtomicLong coolingUntil;
        private final Map<AgentRunBand, AtomicLong> waiting;
        private final AtomicLong limit;
        private final AtomicLong baselineMillis;

        private Pool(
                String slug,
                Map<AgentRunBand, AtomicInteger> active,
                AtomicLong coolingUntil,
                Map<AgentRunBand, AtomicLong> waiting,
                AtomicLong limit,
                AtomicLong baselineMillis) {
            this.slug = slug;
            this.active = active;
            this.coolingUntil = coolingUntil;
            this.waiting = waiting;
            this.limit = limit;
            this.baselineMillis = baselineMillis;
        }

        AtomicInteger active(AgentRunBand band) {
            return Objects.requireNonNull(active.get(band));
        }

        void waited(AgentRunBand band, String reason, long nanos) {
            waitTimer(slug, band, reason).record(nanos, TimeUnit.NANOSECONDS);
        }

        void abandoned(AgentRunBand band, String reason) {
            abandonedCounter(slug, band, reason).increment();
        }

        void cancelled(String reason) {
            cancelledCounter(slug, reason).increment();
        }

        void granted(AgentRunBand band) {
            grantedCounter(slug, band).increment();
        }

        void throttled(int status) {
            throttledCounter(slug, status).increment();
        }

        void limitDecreased(String cause) {
            limitDecreasesCounter(slug, cause).increment();
        }

        void firstToken(AgentRunBand band, int millis) {
            firstTokenTimer(slug, band).record(millis, TimeUnit.MILLISECONDS);
        }

        void called(AgentRunBand band, String outcome, long nanos) {
            operationTimer(slug, band).record(nanos, TimeUnit.NANOSECONDS);
            callsCounter(slug, band, outcome).increment();
        }

        void tokens(AgentRunBand band, ProxyTokenUsage usage) {
            tokensCounter(slug, band, "input").increment(usage.billableInputTokens());
            tokensCounter(slug, band, "output").increment(usage.outputTokens());
            tokensCounter(slug, band, "cache_read").increment(usage.cacheReadTokens());
            tokensCounter(slug, band, "cache_write").increment(usage.cacheWriteTokens());
        }

        void baselineReset() {
            baselineResetsCounter(slug).increment();
        }

        void absorbed() {
            absorbedCounter(slug).increment();
        }

        void expired(long permits) {
            expiredCounter(slug).increment((double) permits);
        }

        void bucketReached(String bucket) {
            bucketReachedCounter(slug, bucket).increment();
        }

        /** Records that the pool is in a cooldown for {@code millis} from now, as this server knows it. */
        void coolingFor(long millis) {
            long until = System.nanoTime() + millis * 1_000_000;
            coolingUntil.accumulateAndGet(until, (current, next) -> current - next > 0 ? current : next);
        }
    }
}
