package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAttemptUsageService.CallSettlement;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting.BilledAttempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnUsage;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adds one proxied call's token usage to the conversation turn attempt that made it — the mentor's counterpart to
 * {@link ProxyUsageAccumulator}. Runs in its own {@code REQUIRES_NEW} transaction: the proxy servlet thread has no
 * ambient transaction, and the accounting write must commit independently of the passthrough.
 *
 * <p>The call lands on the attempt's usage measurement ({@link MentorAttemptUsageService#settleCall}), also when it
 * returns after the attempt ended or the turn started its next attempt; that attempt's ledger row is then projected
 * again. While the attempt holds its lease, the turn row takes the call too, because the turn credential reads the
 * attempt's spend from it on every call. {@link LlmProxyService} settles a forwarded call at most once.
 *
 * <p>The transaction is opened inside the failure handling: a settlement that throws marks it rollback-only, and its
 * commit then throws too.
 *
 * <p>A dropped call is money lost: the turn is gone, or the attempt has no measurement because it was admitted before
 * the measurement existed or an erasure removed it. A non-zero rate on {@code llm.proxy.usage.mentor.superseded} or
 * {@code llm.proxy.usage.mentor.failure} means the ledger is under-billing.
 */
@Service
@ConditionalOnWorkerRole
public class MentorTurnUsageAccumulator {

    private static final Logger log = LoggerFactory.getLogger(MentorTurnUsageAccumulator.class);

    private final MentorAttemptUsageService attemptUsage;
    private final TransactionTemplate ownTransaction;
    private final MeterRegistry meterRegistry;

    MentorTurnUsageAccumulator(
            MentorAttemptUsageService attemptUsage,
            PlatformTransactionManager transactionManager,
            MeterRegistry meterRegistry) {
        this.attemptUsage = attemptUsage;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.meterRegistry = meterRegistry;
    }

    /** Never throws, so accounting can never break the proxied response. */
    public void accumulate(BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
        if (usage == null) {
            return;
        }
        UUID turnId = attempt.sourceId();
        var call = new MentorTurnUsage(
                usage.billableInputTokens(),
                usage.outputTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                usage.reasoningTokens(),
                1);
        try {
            CallSettlement settled =
                    ownTransaction.execute(status -> attemptUsage.settleCall(turnId, attempt.number(), call));
            if (settled == CallSettlement.OWNER_GONE || settled == CallSettlement.UNREGISTERED) {
                recordDropped(turnId, settled);
            }
        } catch (RuntimeException e) {
            log.warn("Lost proxy usage accounting for conversation turn {} — this call may go unbilled", turnId, e);
            meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_FAILURE).increment();
        }
    }

    /** The call has no attempt to land on; creating one for it would bring back what an erasure removed. */
    private void recordDropped(UUID turnId, CallSettlement reason) {
        log.warn(
                "Dropping proxy usage of conversation turn {} with no attempt measurement to settle on ({}); this call goes"
                        + " unbilled",
                turnId,
                reason);
        meterRegistry.counter(AgentMetrics.LLM_PROXY_USAGE_MENTOR_SUPERSEDED).increment();
    }
}
