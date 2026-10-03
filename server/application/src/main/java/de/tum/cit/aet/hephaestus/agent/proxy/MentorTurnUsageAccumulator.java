package de.tum.cit.aet.hephaestus.agent.proxy;

import static de.tum.cit.aet.hephaestus.core.TransactionCallbacks.afterCommit;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting.BilledAttempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds one proxied call's token usage to the mentor turn that made it — the mentor's counterpart to
 * {@link ProxyUsageAccumulator}, writing to the {@code chat_message} row instead of an
 * {@code agent_job} one. Runs in its own {@code REQUIRES_NEW} transaction: the proxy servlet thread
 * has no ambient transaction, and the accounting write must commit independently of the passthrough.
 *
 * <p>The row, not the process-local {@link MentorTurnMeter}, is the record: a worker that dies would
 * otherwise take the whole turn's spend with it.
 *
 * <p>A dropped write is money lost, not a no-op — these columns are the only record of a crashed
 * turn's calls, so a non-zero rate on {@code llm.proxy.usage.mentor.superseded} or
 * {@code llm.proxy.usage.mentor.failure} means the ledger is under-billing.
 */
@Service
@ConditionalOnWorkerRole
public class MentorTurnUsageAccumulator {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnUsageAccumulator.class);

    private final ChatMessageRepository chatMessageRepository;
    private final MentorProxyCredentialRegistry credentialRegistry;
    private final MeterRegistry meterRegistry;

    MentorTurnUsageAccumulator(
            ChatMessageRepository chatMessageRepository,
            MentorProxyCredentialRegistry credentialRegistry,
            MeterRegistry meterRegistry) {
        this.chatMessageRepository = chatMessageRepository;
        this.credentialRegistry = credentialRegistry;
        this.meterRegistry = meterRegistry;
    }

    /** Never throws, so accounting can never break the proxied response. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void accumulate(@Nullable BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
        if (attempt == null || usage == null) {
            return;
        }
        UUID turnId = attempt.sourceId();
        try {
            int rows = chatMessageRepository.accumulateLlmUsage(
                    turnId,
                    usage.billableInputTokens(),
                    usage.outputTokens(),
                    usage.reasoningTokens(),
                    usage.cacheReadTokens(),
                    usage.cacheWriteTokens());
            if (rows == 0) {
                recordSuperseded(turnId);
                return;
            }
            mirrorOntoMeterAfterCommit(turnId, usage);
        } catch (RuntimeException e) {
            log.warn("Lost proxy usage accounting for mentor turn {} — this call may go unbilled", turnId, e);
            meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_FAILURE).increment();
        }
    }

    /**
     * A transaction that fails at commit must not leave the meter holding spend the row never got,
     * which would refuse calls the workspace had headroom for. A miss is not an error: the turn has
     * stopped owning its sandbox, and the money is already on the row.
     */
    private void mirrorOntoMeterAfterCommit(UUID turnId, ProxyTokenUsage usage) {
        afterCommit(() -> credentialRegistry.accumulate(turnId, usage));
    }

    /**
     * The turn went terminal after this call was authenticated, so its totals have already been billed.
     * Adding now would double-bill; dropping under-bills by one call, the lesser error.
     */
    private void recordSuperseded(UUID turnId) {
        log.warn(
                "Dropping proxy usage from a finished mentor turn — turn {} is no longer in flight; "
                        + "this call goes unbilled rather than being added to a turn that has already been billed",
                turnId);
        meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_SUPERSEDED).increment();
    }
}
