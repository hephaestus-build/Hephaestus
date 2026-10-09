package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting.BilledAttempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds one proxied call's token usage to the Heph turn attempt that made it — the mentor's counterpart to
 * {@link ProxyUsageAccumulator}, writing to the {@code mentor_turn} row instead of an
 * {@code agent_job} one. Runs in its own {@code REQUIRES_NEW} transaction: the proxy servlet thread
 * has no ambient transaction, and the accounting write must commit independently of the passthrough.
 *
 * <p>The row is both the record and the budget gate's read model: the turn credential reads the attempt's spend
 * from it on every call, so a worker that dies still bills what the proxy recorded.
 *
 * <p>A dropped write is money lost, not a no-op — these columns are the only record of a crashed
 * turn's calls, so a non-zero rate on {@code llm.proxy.usage.mentor.superseded} or
 * {@code llm.proxy.usage.mentor.failure} means the ledger is under-billing.
 */
@Service
@ConditionalOnWorkerRole
public class MentorTurnUsageAccumulator {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnUsageAccumulator.class);

    private final MentorTurnRepository turnRepository;
    private final MeterRegistry meterRegistry;

    MentorTurnUsageAccumulator(MentorTurnRepository turnRepository, MeterRegistry meterRegistry) {
        this.turnRepository = turnRepository;
        this.meterRegistry = meterRegistry;
    }

    /** Never throws, so accounting can never break the proxied response. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accumulate(BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
        if (usage == null) {
            return;
        }
        UUID turnId = attempt.sourceId();
        try {
            int rows = turnRepository.accumulateUsage(
                    turnId,
                    attempt.number(),
                    new MentorTurnUsage(
                            usage.billableInputTokens(),
                            usage.outputTokens(),
                            usage.cacheReadTokens(),
                            usage.cacheWriteTokens(),
                            usage.reasoningTokens(),
                            1));
            if (rows == 0) {
                recordSuperseded(turnId);
            }
        } catch (RuntimeException e) {
            log.warn("Lost proxy usage accounting for Heph turn {} — this call may go unbilled", turnId, e);
            meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_FAILURE).increment();
        }
    }

    /**
     * The attempt ended after this call was authenticated, so its totals have already been billed.
     * Adding now would double-bill; dropping under-bills by one call, the lesser error.
     */
    private void recordSuperseded(UUID turnId) {
        log.warn(
                "Dropping proxy usage from a finished Heph turn attempt — turn {} no longer runs that attempt; "
                        + "this call goes unbilled rather than being added to an attempt that has already been billed",
                turnId);
        meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_SUPERSEDED).increment();
    }
}
