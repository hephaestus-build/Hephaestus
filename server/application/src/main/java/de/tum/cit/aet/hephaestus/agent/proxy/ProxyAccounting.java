package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
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
        // Registered up front, so a rate of refusals starts from zero.
        for (ReviewModelGateway.Refusal refusal : ReviewModelGateway.Refusal.values()) refusedCounter(refusal);
        this.gatewayLimiterErrors = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_LIMITER_ERRORS)
                .description("Sandbox gateway requests served without a rate-limit decision")
                .register(meterRegistry);
        this.practiceScriptUnattributed = Counter.builder(AgentMetrics.LLM_PROXY_PRACTICE_SCRIPT_UNATTRIBUTED)
                .description("Practice script calls refused because they named no valid practice")
                .register(meterRegistry);
    }

    /**
     * Whether the payer of THIS call has crossed their monthly cap, counting what the workspace's running
     * work has spent, which the ledger cannot see until each run ends.
     */
    public boolean refuseForBudget(ProxyRouting routing) {
        return budgetBlocked(budgetGate.isBlocked(routing), UsageFundingSource.capTag(routing.connectionScope()));
    }

    public boolean refuseForDirectModelCap(ProxyRouting routing, ProxyRouting.BilledAttempt attempt) {
        return budgetBlocked(budgetGate.isDirectModelCapReached(attempt), "practice_script_attempt");
    }

    // The admission points count their refusals at a cap in the same counter, under their own surface.
    private boolean budgetBlocked(boolean blocked, String cap) {
        if (blocked) {
            meterRegistry
                    .counter(AgentMetrics.LLM_BUDGET_BLOCKED, "surface", "model_call", "cap", cap)
                    .increment();
        }
        return blocked;
    }

    /**
     * A call that the proxy refused before it reached a provider. A model that declines to answer is no refusal here: it
     * is the {@code refused} outcome of its call.
     */
    public void refused(ReviewModelGateway.Refusal refusal) {
        refusedCounter(refusal).increment();
    }

    private Counter refusedCounter(ReviewModelGateway.Refusal refusal) {
        return Counter.builder(AgentMetrics.LLM_PROXY_REFUSED)
                .description("Model calls that the proxy refused before they reached their provider")
                .tag("refusal", refusal.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
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
