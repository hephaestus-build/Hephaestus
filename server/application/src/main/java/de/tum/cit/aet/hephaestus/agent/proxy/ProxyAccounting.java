package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
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
    private final Counter callsInFlightRefused;
    private final Counter gatewayLimiterErrors;
    private final Counter practiceScriptUnattributed;

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
        this.callsInFlightRefused = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_CALLS_IN_FLIGHT_REFUSED)
                .description("Model calls refused because their attempt already had its most calls in flight")
                .register(meterRegistry);
        this.gatewayLimiterErrors = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_LIMITER_ERRORS)
                .description("Sandbox gateway requests served without a rate-limit decision")
                .register(meterRegistry);
        this.practiceScriptUnattributed = Counter.builder(AgentMetrics.LLM_PROXY_PRACTICE_SCRIPT_UNATTRIBUTED)
                .description("Practice script calls refused because they named no valid practice")
                .register(meterRegistry);
    }

    /**
     * Whether the payer of THIS call has crossed their monthly cap, counting the calling attempt's own
     * spend, which the ledger cannot see until the run ends.
     */
    public boolean refuseForBudget(ProxyRouting routing) {
        return countedRefusal(
                budgetGate.isBlocked(routing), routing, UsageFundingSource.capTag(routing.connectionScope()));
    }

    public boolean refuseForDirectModelCap(ProxyRouting routing, ProxyRouting.BilledAttempt attempt) {
        return countedRefusal(budgetGate.isDirectModelCapReached(attempt), routing, "practice_script_attempt");
    }

    // Monthly and per-attempt limits have different owners; count refusal against the cap that blocked it.
    private boolean countedRefusal(boolean refused, ProxyRouting routing, String cap) {
        if (refused) {
            meterRegistry
                    .counter(AgentMetrics.LLM_PROXY_BUDGET_BLOCKED, "apiProtocol", routing.apiProtocol(), "cap", cap)
                    .increment();
        }
        return refused;
    }

    // Every direct call must name a valid practice so its spend can be attributed.
    public void recordUnattributedPracticeScript() {
        practiceScriptUnattributed.increment();
    }

    /**
     * Never throws: a body this cannot read must not turn a call the provider already served — and
     * already charged us for — into an error for the runner. Counted instead, since an unreadable body
     * means this call's tokens were billed to nobody.
     *
     * @param practiceScript the practice script call; {@code null} for every other call
     * @return the usage that was recorded; {@code null} when the body reports none or cannot be read
     */
    public @Nullable ProxyTokenUsage recordUsage(
            ProxyRouting.BilledAttempt attempt,
            @Nullable PracticeScriptCall practiceScript,
            LlmApiProtocol protocol,
            byte[] upstreamBody) {
        ProxyTokenUsage usage;
        try {
            usage = ProxyTokenUsage.from(objectMapper.readTree(upstreamBody), protocol);
        } catch (Exception e) {
            // A parser's message quotes the input it rejected, and this input is the model's answer. The type
            // tells a non-JSON answer from an unreadable usage block without it.
            log.atWarn()
                    .addKeyValue("event.name", "llm.proxy.usage.unparseable")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log("Could not parse upstream usage for {} — this call's tokens go unbilled", attempt.sourceId());
            recordMalformedUsage(attempt);
            // An unreadable body is not also an absent one: the practice script row still counts the
            // call, and only the unparseable counter records the gap.
            if (practiceScript != null) {
                usageAccumulator.accumulate(attempt, practiceScript, null);
            }
            return null;
        }
        recordUsage(attempt, practiceScript, usage);
        return usage;
    }

    /**
     * A practice script call counts on its row even when its provider reports no tokens, so the row shows
     * every call. A practice script call on the chat model also goes into the attempt's own total, because the
     * review's ledger row bills the chat model; the two change in one settlement. A served response without
     * reported usage is counted by source type; tokens and provider charges are never estimated.
     */
    public void recordUsage(
            ProxyRouting.BilledAttempt attempt,
            @Nullable PracticeScriptCall practiceScript,
            @Nullable ProxyTokenUsage usage) {
        if (usage == null) {
            meterRegistry
                    .counter(
                            AgentMetrics.LLM_PROXY_USAGE_MISSING,
                            "sourceType",
                            attempt.sourceType().name())
                    .increment();
        }
        switch (attempt.sourceType()) {
            case MENTOR_TURN -> {
                if (usage != null) mentorTurnUsageAccumulator.accumulate(attempt, usage);
            }
            case REVIEW_JOB, PRACTICE_SCRIPT_DECISION, PRACTICE_SCRIPT_EMBEDDING, PRACTICE_SCRIPT_RERANKING -> {
                // The review's own call without usage has nothing to settle; it stays the missing-usage count.
                if (practiceScript != null || usage != null)
                    usageAccumulator.accumulate(attempt, practiceScript, usage);
            }
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

    public void recordCallsInFlightRefused() {
        callsInFlightRefused.increment();
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

    /** An upstream call sent no byte for its idle bound, so the proxy cut it. */
    public void recordUpstreamStalled(String apiProtocol) {
        meterRegistry
                .counter(AgentMetrics.LLM_PROXY_UPSTREAM_STALLED, "apiProtocol", apiProtocol)
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
