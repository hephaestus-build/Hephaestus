package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallDelay;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallLimit;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermitRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPoolRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The limit of a model pool, learned from the provider's answers to the calls of {@link LlmCallPermits}: a refusal, a
 * served head that says a bucket is empty, a first token, and a stream without content lower it or pause the pool, and
 * a served call that found the limit binding raises it. It writes the pool meters of the permits that built it.
 */
final class LlmPoolLearning {
    private static final Logger log = LoggerFactory.getLogger(LlmPoolLearning.class);
    static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(5);

    /** OpenAI and Anthropic answer 429 with these codes when an account has no quota left, which no wait mends. */
    private static final List<String> SPENT_ACCOUNT = List.of("insufficient_quota", "enforced_spend_limit_reached");

    private final LlmCallPoolRepository pools;
    private final LlmCallPermitRepository rows;
    private final TransactionTemplate transactions;
    private final LlmCallPoolProperties properties;
    private final LlmPoolMeters meters;

    LlmPoolLearning(
            LlmCallPoolRepository pools,
            LlmCallPermitRepository rows,
            TransactionTemplate transactions,
            LlmCallPoolProperties properties,
            LlmPoolMeters meters) {
        this.pools = pools;
        this.rows = rows;
        this.transactions = transactions;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * The provider's answer that it serves too many calls: 429, 529, or a 503. Only one of the calls that started under
     * the same limit lowers it, since the others say nothing new, and all extend the pause.
     *
     * <p>Another tenant's calls may not depend on a workspace's own credential, so in a pool that models share, such a
     * credential changes nothing and {@code null} tells the caller that its calls did not slow down.
     *
     * @return how long the provider asked to be left alone, uncapped; {@code null} when the answer changed nothing
     */
    @Nullable
    Duration throttled(LlmCallPermits.Permit permit, int status, HttpHeaders headers, boolean instanceCredential) {
        Duration asked = RetryAfter.parse(headers, Instant.now());
        Duration pause = asked == null ? DEFAULT_COOLDOWN : asked;
        Duration capped = pause.compareTo(properties.maxCooldown()) > 0 ? properties.maxCooldown() : pause;
        long millis = Math.max(1, capped.toMillis());
        boolean applied = Boolean.TRUE.equals(transactions.execute(state -> {
            var pool = pools.lockAdmission(permit.poolId).orElse(null);
            if (pool == null || (!instanceCredential && !pool.getDedicated())) return false;
            if (Objects.equals(pool.getThrottleMark(), permit.throttleMark)) {
                pools.throttled(
                        permit.poolId,
                        new LlmCallLimit(LlmPoolRoom.limit(pool), pool.getLimitCredit())
                                .afterThrottle()
                                .learned());
                meters.pool(permit.pool).limitDecreased("refusal");
            }
            pools.coolDown(permit.poolId, millis);
            return true;
        }));
        meters.pool(permit.pool).throttled(status);
        if (!applied) return null;
        meters.pool(permit.pool).coolingFor(millis);
        return pause;
    }

    /**
     * The head of a served response. When the provider's own headers say that a bucket is empty, every caller pauses
     * briefly. The limit stays: nothing was refused. This runs on the transport's event loop, so the row is written
     * beside it, and the tenant rule of a refusal applies.
     */
    void served(LlmCallPermits.Permit permit, HttpHeaders headers, boolean instanceCredential) {
        var reached = RateLimitHeaders.reached(headers, Instant.now());
        if (reached == null) return;
        long millis =
                Math.min(reached.pause().toMillis(), properties.maxCooldown().toMillis());
        var _ = Thread.ofVirtual().name("llm-bucket-reached").start(() -> {
            try {
                boolean applied = Boolean.TRUE.equals(transactions.execute(status -> {
                    var pool = pools.lockAdmission(permit.poolId).orElse(null);
                    if (pool == null || (!instanceCredential && !pool.getDedicated())) return false;
                    pools.coolDown(permit.poolId, millis);
                    return true;
                }));
                if (!applied) return;
                meters.pool(permit.pool).bucketReached(reached.bucket());
                meters.pool(permit.pool).coolingFor(millis);
            } catch (RuntimeException failed) {
                log.warn(
                        "Provider bucket pause not recorded: pool={}, error={}",
                        permit.pool,
                        failed.getClass().getSimpleName());
            }
        });
    }

    /** Whether a refusal's body says that the account has no quota left, which is no answer about capacity. */
    static boolean spentAccount(byte @Nullable [] body) {
        if (body == null) return false;
        String answer = new String(body, StandardCharsets.UTF_8);
        return SPENT_ACCOUNT.stream().anyMatch(answer::contains);
    }

    /** Whether a provider's status says it serves too many calls. */
    static boolean throttles(int status) {
        return status == 429 || status == 503 || status == 529;
    }

    /**
     * A stream's first content event. The caller is about to relay it to someone who waits for it, so the row is
     * written beside it. The same tenant rule as for a refusal applies. A call under an older limit says nothing about
     * this one.
     */
    void firstToken(LlmCallPermits.Permit permit, long nanos, boolean instanceCredential) {
        int millis = Math.toIntExact(Math.min(TimeUnit.NANOSECONDS.toMillis(nanos), Integer.MAX_VALUE));
        permit.firstTokenMillis = millis;
        meters.pool(permit.pool).firstToken(permit.band, millis);
        var _ = Thread.ofVirtual().name("llm-first-token").start(() -> {
            try {
                transactions.executeWithoutResult(status -> learnFromFirstToken(permit, millis, instanceCredential));
            } catch (RuntimeException failed) {
                log.warn(
                        "Model call first token not recorded: pool={}, error={}",
                        permit.pool,
                        failed.getClass().getSimpleName());
            }
        });
    }

    private void learnFromFirstToken(LlmCallPermits.Permit permit, int millis, boolean instanceCredential) {
        var pool = pools.lockAdmission(permit.poolId).orElse(null);
        if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
        if (!instanceCredential && !pool.getDedicated()) return;
        int limit = LlmPoolRoom.limit(pool);
        var known =
                new LlmCallDelay(pool.getBaselineFirstTokenMs(), pool.getWindowFirstTokenMs(), pool.getWindowSamples());
        var step = known.afterFirstToken(millis, limit);
        var delay = step.delay();
        pools.firstTokens(permit.poolId, delay.baselineMillis(), delay.windowMillis(), delay.samples());
        if (step.keep() < 1) {
            pools.throttled(
                    permit.poolId,
                    new LlmCallLimit(limit, pool.getLimitCredit())
                            .afterDelay(step.keep())
                            .learned());
            meters.pool(permit.pool).limitDecreased("delay");
        } else if (known.baselineMillis() != null
                && delay.baselineMillis() != null
                && delay.baselineMillis() > known.baselineMillis())
            meters.pool(permit.pool).baselineReset();
    }

    /**
     * A served stream that ended without a content token. A provider can fail this way with no error: it sends its
     * head, a comment or a role, and then nothing, or it ends the stream. Such a call never raises the limit. It lowers
     * the limit like a refusal when the provider stopped answering: it ended the stream, or sent no content for the
     * proxy's silence bound, {@code hephaestus.conversation.turn.model-idle-timeout}. Without that, it lowers the limit
     * when the call waited past what the first-token baseline allows. A caller that ended the call sooner says nothing
     * about the provider.
     */
    void unanswered(LlmCallPermits.Permit permit, boolean stoppedAnswering, long nanos, boolean instanceCredential) {
        permit.unanswered = true;
        int millis = Math.toIntExact(Math.min(TimeUnit.NANOSECONDS.toMillis(nanos), Integer.MAX_VALUE));
        try {
            transactions.executeWithoutResult(status -> {
                var pool = pools.lockAdmission(permit.poolId).orElse(null);
                if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
                if (!instanceCredential && !pool.getDedicated()) return;
                if (!stoppedAnswering && !new LlmCallDelay(pool.getBaselineFirstTokenMs(), null, 0).queued(millis))
                    return;
                pools.throttled(
                        permit.poolId,
                        new LlmCallLimit(LlmPoolRoom.limit(pool), pool.getLimitCredit())
                                .afterThrottle()
                                .learned());
                meters.pool(permit.pool).limitDecreased("unanswered");
            });
        } catch (RuntimeException failed) {
            log.warn(
                    "Unanswered model call not recorded: pool={}, error={}",
                    permit.pool,
                    failed.getClass().getSimpleName());
        }
    }

    /**
     * Raises the limit for a served call that started under it and found it binding; the caller is in a transaction.
     */
    void learn(LlmCallPermits.Permit permit) {
        if (permit.unanswered) return;
        var pool = pools.lockAdmission(permit.poolId).orElse(null);
        if (pool == null || !Objects.equals(pool.getThrottleMark(), permit.throttleMark)) return;
        if (!permit.instanceCredential && !pool.getDedicated()) return;
        Integer maximum = pool.getMaxConcurrentCalls();
        if (maximum != null && pool.getLearnedLimit() >= maximum) return;
        // A call that did not stream shows no queue, so it cannot raise a limit that first tokens watch.
        var delay = new LlmCallDelay(pool.getBaselineFirstTokenMs(), null, 0);
        Integer waited = permit.firstTokenMillis;
        if (waited == null ? delay.baselineMillis() != null : delay.queued(waited)) return;
        int limit = LlmPoolRoom.limit(pool);
        if (rows.countActive(permit.poolId) < limit
                && rows.countNotInteractive(permit.poolId) < limit - LlmCallLimit.reserve(limit)) return;
        var next = new LlmCallLimit(pool.getLearnedLimit(), pool.getLimitCredit()).afterServedCall(pool.getProbing());
        pools.learned(permit.poolId, next.learned(), next.credit());
    }
}
