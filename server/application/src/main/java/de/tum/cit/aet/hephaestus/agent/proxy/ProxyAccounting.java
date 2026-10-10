package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Everything the LLM proxy records or decides about a request's cost. */
// Gated like the worker-only accumulators it depends on; an ungated bean here would fail the context
// of every tier that runs with the worker role off.
@Component
@ConditionalOnWorkerRole
public class ProxyAccounting {

    private static final Logger log = LoggerFactory.getLogger(ProxyAccounting.class);

    private final ProxyBudgetGate budgetGate;
    private final ProxyUsageAccumulator usageAccumulator;
    private final MentorTurnUsageAccumulator mentorTurnUsageAccumulator;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    // Only the request meters are the LLM capability's own; the rate limiter guards every capability
    // the gateway chain carries, so its meters take no capability tag.
    private final Counter gatewayRequests;
    private final DistributionSummary gatewayRequestSize;
    private final Counter gatewayThrottled;
    private final Counter gatewayLimiterErrors;
    private final Counter precomputeUnattributed;

    ProxyAccounting(
            ProxyBudgetGate budgetGate,
            ProxyUsageAccumulator usageAccumulator,
            MentorTurnUsageAccumulator mentorTurnUsageAccumulator,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.budgetGate = budgetGate;
        this.usageAccumulator = usageAccumulator;
        this.mentorTurnUsageAccumulator = mentorTurnUsageAccumulator;
        this.meterRegistry = meterRegistry;
        this.objectMapper = objectMapper;
        this.gatewayRequests = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_REQUESTS)
                .description("Sandbox gateway requests served")
                .tag("capability", "llm")
                .register(meterRegistry);
        this.gatewayRequestSize = DistributionSummary.builder(AgentMetrics.SANDBOX_GATEWAY_REQUEST_SIZE)
                .description("Sandbox gateway request body size")
                .baseUnit("bytes")
                .tag("capability", "llm")
                .register(meterRegistry);
        this.gatewayThrottled = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_THROTTLED)
                .description("Sandbox gateway requests refused by the per-principal rate limit")
                .register(meterRegistry);
        this.gatewayLimiterErrors = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_LIMITER_ERRORS)
                .description("Sandbox gateway requests served without a rate-limit decision")
                .register(meterRegistry);
        this.precomputeUnattributed = Counter.builder(AgentMetrics.LLM_PROXY_PRECOMPUTE_UNATTRIBUTED)
                .description("Precompute calls refused because they named no valid practice")
                .register(meterRegistry);
    }

    /**
     * Whether the payer of THIS call has crossed their monthly cap, counting the calling attempt's own
     * spend, which the ledger cannot see until the run ends.
     */
    public boolean refuseForBudget(ProxyRouting routing) {
        return countedRefusal(budgetGate.isBlocked(routing), routing, FundingSource.capTag(routing.connectionScope()));
    }

    public boolean refuseForPrecomputeCap(ProxyRouting routing, ProxyRouting.BilledAttempt attempt) {
        return countedRefusal(budgetGate.isPrecomputeCapReached(attempt), routing, "precompute_attempt");
    }

    /**
     * Counted by the cap that refused, because different people fix each one: an admin raises a monthly
     * budget ({@link FundingSource#capTag}), and an operator raises the per-attempt precompute token cap
     * ({@code precompute_attempt}).
     */
    private boolean countedRefusal(boolean refused, ProxyRouting routing, String cap) {
        if (refused) {
            meterRegistry
                    .counter(AgentMetrics.LLM_PROXY_BUDGET_BLOCKED, "apiProtocol", routing.apiProtocol(), "cap", cap)
                    .increment();
        }
        return refused;
    }

    /**
     * A credential authenticated but named no execution to bill. The rate is a defect signal, not a
     * usage statistic: a sandbox is calling outside the window its turn owns.
     */
    public void recordUnbillableRefusal(String apiProtocol) {
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_UNBILLABLE_REFUSED, "apiProtocol", apiProtocol)
                .increment();
    }

    /**
     * A precompute call named no practice, or no valid one, so its spend could not be split by practice.
     * Any rate is a defect: the precompute runner names the practice on every call.
     */
    public void recordUnattributedPrecompute() {
        precomputeUnattributed.increment();
    }

    /**
     * Never throws: a body this cannot read must not turn a call the provider already served — and
     * already charged us for — into an error for the runner. Counted instead, since an unreadable body
     * means this call's tokens were billed to nobody.
     *
     * @param precompute the precompute call; {@code null} for every other call
     */
    public void recordUsage(
            ProxyRouting.BilledAttempt attempt,
            @Nullable PrecomputeCall precompute,
            LlmApiProtocol protocol,
            byte[] upstreamBody) {
        ProxyTokenUsage usage;
        try {
            usage = ProxyTokenUsage.from(objectMapper.readTree(upstreamBody), protocol);
        } catch (Exception e) {
            log.warn("Could not parse upstream usage for {} — this call's tokens go unbilled", attempt.sourceId(), e);
            recordMalformedUsage(attempt);
            usage = null;
        }
        recordUsage(attempt, precompute, usage);
    }

    /**
     * A precompute call counts on its row even when its provider reports no tokens, so the row shows
     * every call. A precompute call on the chat model also goes into the job's own counters, because the
     * review's ledger row bills the chat model.
     */
    public void recordUsage(
            ProxyRouting.BilledAttempt attempt, @Nullable PrecomputeCall precompute, @Nullable ProxyTokenUsage usage) {
        if (precompute != null) {
            usageAccumulator.accumulatePrecompute(attempt, precompute, usage);
        }
        if (usage == null) {
            return;
        }
        switch (attempt.sourceType()) {
            case AGENT_JOB -> usageAccumulator.accumulate(attempt, usage);
            case MENTOR_TURN -> mentorTurnUsageAccumulator.accumulate(attempt, usage);
            case PRECOMPUTE_DECISION, PRECOMPUTE_EMBEDDING, PRECOMPUTE_RERANKING -> {}
        }
    }

    public void recordMalformedUsage(ProxyRouting.BilledAttempt attempt) {
        meterRegistry
                .counter(
                        AgentMetrics.LLM_PROXY_USAGE_UNPARSEABLE,
                        "sourceType",
                        attempt.sourceType().name())
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    /** Counted once the gateway has accepted the request, so it measures work served, not probes. */
    public void recordGatewayRequest(int requestBytes) {
        gatewayRequests.increment();
        gatewayRequestSize.record(requestBytes);
    }

    public void recordGatewayThrottled() {
        gatewayThrottled.increment();
    }

    /**
     * The store behind the gateway's rate limit could not be reached, so the request was served
     * without a limit. A sustained rate means the gateway is unlimited, which no log line makes
     * alertable on its own.
     */
    public void recordGatewayLimiterError() {
        gatewayLimiterErrors.increment();
    }

    public void stopTimer(Timer.Sample sample, String apiProtocol) {
        sample.stop(Timer.builder(AgentMetrics.LLM_PROXY_DURATION)
                .description("LLM proxy request duration")
                .tag("apiProtocol", apiProtocol)
                .register(meterRegistry));
    }

    public void recordError(String apiProtocol) {
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_ERRORS, "apiProtocol", apiProtocol)
                .increment();
    }

    /**
     * An upstream refused the streamed-usage request the proxy adds, so its tokens cannot be read off
     * the stream. A sustained rate means every streamed call on that connection is under-billed.
     */
    public void recordStreamUsageUnsupported(String apiProtocol) {
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_STREAM_USAGE_UNSUPPORTED, "apiProtocol", apiProtocol)
                .increment();
    }
}
