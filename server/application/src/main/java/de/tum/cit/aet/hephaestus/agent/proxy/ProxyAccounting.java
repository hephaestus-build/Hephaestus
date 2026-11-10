package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
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
// Accounting depends on the budget gate, so both require the agent runtime.
@Component
@ConditionalOnAgentRuntime
public class ProxyAccounting {

    private static final Logger log = LoggerFactory.getLogger(ProxyAccounting.class);

    private final ProxyBudgetGate budgetGate;
    private final ProxyCallLedger callLedger;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    // Only the request meters are the LLM capability's own; the rate limiter guards every capability
    // the gateway chain carries, so its meters take no capability tag.
    private final Counter gatewayRequests;
    private final DistributionSummary gatewayRequestSize;
    private final Counter gatewayLimiterErrors;

    ProxyAccounting(
            ProxyBudgetGate budgetGate,
            ProxyCallLedger callLedger,
            MeterRegistry meterRegistry,
            ObjectMapper objectMapper) {
        this.budgetGate = budgetGate;
        this.callLedger = callLedger;
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
        // Registered up front, so a rate of refusals starts from zero.
        for (SandboxGatewayRefusal refusal : SandboxGatewayRefusal.values()) gatewayRefusedCounter(refusal);
        for (ProxyRefusal refusal : ProxyRefusal.values()) refusedCounter(refusal);
        this.gatewayLimiterErrors = Counter.builder(AgentMetrics.SANDBOX_GATEWAY_LIMITER_ERRORS)
                .description("Sandbox gateway requests served without a rate-limit decision")
                .register(meterRegistry);
    }

    /**
     * Whether the payer of THIS call has crossed their monthly cap, counting every call of the month: an open or
     * unmeasured call at its upper bound.
     */
    public boolean refuseForBudget(ProxyRouting routing) {
        return budgetBlocked(budgetGate.isBlocked(routing), UsageFundingSource.capTag(routing.connectionScope()));
    }

    /** A call whose upper bound did not fit under its payer's monthly cap ({@link ProxyBudgetGate#refusesHold}). */
    public void budgetHeld(ProxyRouting routing) {
        budgetBlocked(true, UsageFundingSource.capTag(routing.connectionScope()));
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
    public void refused(ProxyRefusal refusal) {
        refusedCounter(refusal).increment();
    }

    private Counter refusedCounter(ProxyRefusal refusal) {
        return Counter.builder(AgentMetrics.LLM_PROXY_REFUSED)
                .description("Model calls that the proxy refused before they reached their provider")
                .tag("refusal", refusal.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
    }

    /**
     * Reads the usage that a served answer reports. Never throws: a body this cannot read must not turn a call the
     * provider already served — and already charged us for — into an error for the runner. Counted instead, since an
     * unreadable body leaves the call's measurement unverifiable.
     *
     * @return the usage; {@code null} when the body reports none or cannot be read
     */
    public @Nullable ProxyTokenUsage readUsage(
            ProxyRouting.BilledAttempt attempt, LlmApiProtocol protocol, byte[] upstreamBody) {
        ProxyTokenUsage usage;
        try {
            usage = ProxyTokenUsage.from(objectMapper.readTree(upstreamBody), protocol);
        } catch (Exception e) {
            // A parser's message quotes the input it rejected, and this input is the model's answer. The type
            // tells a non-JSON answer from an unreadable usage block without it.
            log.atWarn()
                    .addKeyValue("event.name", "llm.proxy.usage.unparseable")
                    .addKeyValue("error.type", e.getClass().getName())
                    .log(
                            "Could not parse upstream usage for {} — this call's usage is unverifiable",
                            attempt.sourceId());
            recordMalformedUsage(attempt);
            return null;
        }
        if (usage == null) recordMissingUsage(attempt);
        return usage;
    }

    /** A served answer without reported usage, counted by source type; tokens and charges are never estimated. */
    public void recordMissingUsage(ProxyRouting.BilledAttempt attempt) {
        meterRegistry
                .counter(
                        AgentMetrics.LLM_PROXY_USAGE_MISSING,
                        "sourceType",
                        attempt.sourceType().name())
                .increment();
    }

    /** The usage ledger of each proxied call ({@link ProxyCallLedger}). */
    ProxyCallLedger calls() {
        return callLedger;
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

    void gatewayRefused(SandboxGatewayRefusal refusal) {
        gatewayRefusedCounter(refusal).increment();
    }

    private Counter gatewayRefusedCounter(SandboxGatewayRefusal refusal) {
        return Counter.builder(AgentMetrics.SANDBOX_GATEWAY_REFUSED)
                .description("Sandbox gateway requests that the gateway refused itself")
                .tag("reason", refusal.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry);
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
