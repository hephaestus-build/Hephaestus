package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobLlmUsageDelta;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting.BilledAttempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds one proxied call's token usage to the owning {@code agent_job} row, so a job that crashes
 * mid-run still has the calls it made on record and can be billed for them instead of recording zero.
 * Runs in its own {@code REQUIRES_NEW} transaction — the proxy servlet thread has no ambient
 * transaction, and the accounting write must commit independently of the passthrough.
 *
 * <p>A failed write is money lost, not a no-op: these running totals are what a run without a
 * {@code usage.json} is billed on, so a non-zero rate on {@code llm.proxy.usage.accumulate.failure}
 * means the ledger is under-billing.
 */
@Service
@ConditionalOnWorkerRole
public class ProxyUsageAccumulator {

    private static final Logger log = LoggerFactory.getLogger(ProxyUsageAccumulator.class);

    private final AgentJobRepository agentJobRepository;
    private final MeterRegistry meterRegistry;

    ProxyUsageAccumulator(AgentJobRepository agentJobRepository, MeterRegistry meterRegistry) {
        this.agentJobRepository = agentJobRepository;
        this.meterRegistry = meterRegistry;
    }

    /** Never throws, so accounting can never break the proxied response. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accumulate(@Nullable BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
        if (attempt == null || usage == null) {
            return;
        }
        UUID jobId = attempt.sourceId();
        try {
            int rows = agentJobRepository.accumulateLlmUsage(
                    jobId,
                    attempt.number(),
                    new AgentJobLlmUsageDelta(
                            usage.billableInputTokens(),
                            usage.outputTokens(),
                            usage.reasoningTokens(),
                            usage.cacheReadTokens(),
                            usage.cacheWriteTokens()));
            if (rows == 0) {
                recordSuperseded(attempt);
            }
        } catch (RuntimeException e) {
            log.warn("Lost proxy usage accounting for job {} — this call may go unbilled", jobId, e);
            meterRegistry
                    .counter(AgentMetrics.LLM_PROXY_USAGE_ACCUMULATE_FAILURE)
                    .increment();
        }
    }

    /**
     * The attempt fence rejected this write: the row has moved on since the token was authenticated.
     * Dropping under-bills by one call; adding would charge one attempt's tokens to another, at
     * another price and possibly from the other purse.
     */
    private void recordSuperseded(BilledAttempt attempt) {
        log.warn(
                "Dropping proxy usage from a superseded attempt — job {} is no longer running attempt {}; "
                        + "this call goes unbilled rather than being charged to whoever owns the row now",
                attempt.sourceId(),
                attempt.number());
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_USAGE_ACCUMULATE_SUPERSEDED)
                .increment();
    }
}
