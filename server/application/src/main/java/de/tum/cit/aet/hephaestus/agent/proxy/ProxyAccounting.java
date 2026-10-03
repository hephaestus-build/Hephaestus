package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
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
    }

    /**
     * Whether the payer of THIS call has crossed their monthly cap, counting the calling attempt's own
     * spend, which the ledger cannot see until the run ends.
     */
    public boolean refuseForBudget(ProxyRouting routing) {
        if (!budgetGate.isBlocked(routing)) {
            return false;
        }
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_BUDGET_BLOCKED, "apiProtocol", routing.apiProtocol())
                .increment();
        return true;
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
     * Never throws: a body this cannot read must not turn a call the provider already served — and
     * already charged us for — into an error for the runner. Counted instead, since an unreadable body
     * means this call's tokens were billed to nobody.
     */
    public void recordUsage(ProxyRouting.BilledAttempt attempt, byte[] upstreamBody, boolean responsesProtocol) {
        try {
            JsonNode parsed = objectMapper.readTree(upstreamBody);
            recordUsage(attempt, ProxyTokenUsage.from(parsed, responsesProtocol));
        } catch (Exception e) {
            log.warn("Could not parse upstream usage for {} — this call's tokens go unbilled", attempt.sourceId(), e);
            recordMalformedUsage(attempt);
        }
    }

    public void recordUsage(ProxyRouting.BilledAttempt attempt, @Nullable ProxyTokenUsage usage) {
        if (usage == null) {
            return;
        }
        switch (attempt.sourceType()) {
            case AGENT_JOB -> usageAccumulator.accumulate(attempt, usage);
            case MENTOR_TURN -> mentorTurnUsageAccumulator.accumulate(attempt, usage);
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
