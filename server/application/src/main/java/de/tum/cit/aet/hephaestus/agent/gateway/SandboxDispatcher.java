package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * Starts the sandboxes that queued runs need (ADR 0079 § Claim, priority and reap). It reads the runs that the claim
 * would offer next, in the claim's order, and requests the sandbox of each one's subject that has none, while the
 * sandboxes of that kind stay within their limit. It leases nothing: each sandbox claims its own run when it pulls.
 *
 * <p>Two replicas may read the same run at once. Each then asks for the subject's sandbox, and the owner's partial
 * unique index on a subject's live sandbox gives both the one sandbox.
 */
public class SandboxDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SandboxDispatcher.class);

    private final AgentSandboxes sandboxes;
    private final AgentRuns runs;
    private final ReviewAttemptCustody custody;
    private final MeterRegistry meters;
    private final Map<AgentSandboxKind, Set<AgentRunKind>> driven = new EnumMap<>(AgentSandboxKind.class);
    private final Map<AgentSandboxKind, Integer> limits;
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ExecutorService passes = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("agent-sandbox-dispatch").daemon(true).factory());

    /**
     * @param limits the most live sandboxes of each kind, in every workspace; a kind without a limit is not dispatched
     */
    public SandboxDispatcher(
            AgentSandboxes sandboxes,
            AgentRuns runs,
            List<SandboxRunKind> kinds,
            Map<AgentSandboxKind, Integer> limits,
            ReviewAttemptCustody custody,
            MeterRegistry meters) {
        this.sandboxes = sandboxes;
        this.runs = runs;
        this.custody = custody;
        this.meters = meters;
        this.limits = Map.copyOf(limits);
        for (SandboxRunKind kind : SandboxRunKind.byKind(kinds).values())
            driven.computeIfAbsent(kind.sandbox(), sandbox -> EnumSet.noneOf(AgentRunKind.class))
                    .add(kind.kind());
        // Registered at start, so an alert on a count that is still zero has a series to read.
        meters.counter(AgentMetrics.AGENT_SANDBOX_DISPATCH_FAILURE);
    }

    /** Asks for a pass soon, on the dispatch's own thread; asks while one waits make no second pass. */
    public void wake() {
        if (pending.compareAndSet(false, true))
            passes.execute(() -> {
                pending.set(false);
                dispatch();
            });
    }

    /** One pass: releases the inputs of ended attempts, then requests the sandboxes that due runs need. */
    public void dispatch() {
        try {
            custody.releaseEnded();
            for (var entry : driven.entrySet()) {
                Integer limit = limits.get(entry.getKey());
                if (limit != null) dispatch(entry.getKey(), entry.getValue(), limit);
            }
        } catch (RuntimeException e) {
            meters.counter(AgentMetrics.AGENT_SANDBOX_DISPATCH_FAILURE).increment();
            log.atWarn()
                    .addKeyValue("event.name", "agent.sandbox.dispatch.failed")
                    .addKeyValue("error.type", e.getClass().getName())
                    .setCause(e)
                    .log("Sandbox dispatch failed; it tries again at its next pass");
        }
    }

    private void dispatch(AgentSandboxKind sandbox, Set<AgentRunKind> kinds, int limit) {
        int room = limit - sandboxes.live(sandbox);
        if (limit <= 0) return;
        for (AgentRuns.DueRun due : runs.due(kinds, limit)) {
            long workspace = due.run().workspaceId();
            if (sandboxes.hasLive(workspace, due.concurrencyKey())) continue;
            if (room <= 0) {
                if (sandbox != AgentSandboxKind.CONVERSATION || !sandboxes.releaseIdleConversation()) return;
                room = limit - sandboxes.live(sandbox);
                if (room <= 0) return;
            }
            sandboxes.ensure(workspace, due.concurrencyKey(), sandbox);
            room -= 1;
            log.atInfo()
                    .addKeyValue("event.name", "agent.sandbox.dispatched")
                    .addKeyValue(StructuredLogKeys.SUBJECT_KEY, due.concurrencyKey())
                    .addKeyValue(StructuredLogKeys.WORKSPACE_ID, workspace)
                    .addKeyValue(StructuredLogKeys.RUN_ID, due.run().id())
                    .log("Sandbox requested for a due run");
        }
    }

    @Override
    public void destroy() {
        passes.shutdownNow();
    }
}
