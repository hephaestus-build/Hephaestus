package de.tum.cit.aet.hephaestus.agent.gateway;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.core.exception.MaintenanceInProgressException;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** How many due runs a pass reads at a time. */
    private static final int PAGE = 64;

    private final AgentSandboxes sandboxes;
    private final AgentRuns runs;
    private final ReviewAttemptCustody custody;
    private final RunAdmission admission;
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
            RunAdmission admission,
            MeterRegistry meters) {
        this.sandboxes = sandboxes;
        this.runs = runs;
        this.custody = custody;
        this.admission = admission;
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
            // A person waits on a conversation, so its sandboxes come before a pass over a long queue of reviews.
            var order = new ArrayList<>(driven.entrySet());
            order.sort(Map.Entry.comparingByKey(
                    Comparator.comparing((AgentSandboxKind sandbox) -> sandbox != AgentSandboxKind.CONVERSATION)));
            for (var entry : order) {
                Integer limit = limits.get(entry.getKey());
                if (limit != null) dispatch(entry.getKey(), entry.getValue(), limit);
            }
        } catch (MaintenanceInProgressException migrating) {
            // A migration began after this pass read its runs: the next pass after the migration requests the rest.
            log.debug("Sandbox dispatch stopped for a database migration");
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
        // A full kind starts nothing, so its pass would hold nothing: the held counts keep the last pass with room.
        if (room <= 0 && sandbox != AgentSandboxKind.CONVERSATION) return;
        var pass = admission.pass(kinds);
        try {
            dispatchAdmitted(sandbox, kinds, limit, room, pass);
        } finally {
            pass.ended();
        }
    }

    private void dispatchAdmitted(
            AgentSandboxKind sandbox, Set<AgentRunKind> kinds, int limit, int free, RunAdmission.Pass pass) {
        int room = free;
        // Held runs keep their place, so the pass reads past them, a page at a time, until its room is used or the
        // queue
        // ends. A claim between two pages can move a run across them; the next pass reads it.
        for (int offset = 0; ; offset += PAGE) {
            var page = runs.due(kinds, offset, PAGE);
            var live = sandboxes.withLive(page);
            pass.read(page);
            for (AgentRuns.DueRun due : page) {
                long workspace = due.run().workspaceId();
                if (live.contains(due)) continue;
                if (room <= 0) {
                    if (sandbox != AgentSandboxKind.CONVERSATION || !sandboxes.releaseIdleConversation()) return;
                    room = limit - sandboxes.live(sandbox);
                    if (room <= 0) return;
                }
                // A run that may not start yet keeps its place in the claim order and gets no sandbox.
                if (!pass.admits(due)) continue;
                sandboxes.ensure(workspace, due.concurrencyKey(), sandbox);
                room -= 1;
                log.atInfo()
                        .addKeyValue("event.name", "agent.sandbox.dispatched")
                        .addKeyValue(StructuredLogKeys.SUBJECT_KEY, due.concurrencyKey())
                        .addKeyValue(StructuredLogKeys.WORKSPACE_ID, workspace)
                        .addKeyValue(StructuredLogKeys.RUN_ID, due.run().id())
                        .log("Sandbox requested for a due run");
            }
            if (page.size() < PAGE) return;
        }
    }

    @Override
    public void destroy() {
        passes.shutdownNow();
    }
}
