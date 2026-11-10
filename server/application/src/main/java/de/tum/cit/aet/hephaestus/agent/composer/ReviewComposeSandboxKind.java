package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobClaimService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobTerminal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A review's composition as a run of its review sandbox (ADR 0079, ADR 0069): the sandbox's composer loop calls the
 * model through the gateway and every composition route, and this kind claims the run, holds its inputs and ends it.
 *
 * <p>The claim decides whether the attempt composes or only withholds what is open with a cause, as for a member who
 * declined AI or a spent budget. That decision lives on the replica that claimed, as the attempt's inputs do
 * ({@link ReviewAttemptCustody}). A run whose decision this replica does not hold, as after a restart, is queued again
 * without spending an attempt, and its next claim decides again: the run never composes on a decision it lost.
 */
@Component
@ConditionalOnWorkerRole
@ConditionalOnBooleanProperty(RuntimeRole.AGENT_ENABLED_PROPERTY)
@WorkspaceAgnostic("Each call names a run, which carries its workspace, and the gateway checked it")
public class ReviewComposeSandboxKind implements SandboxRunKind {
    private final ReviewJobClaimService claims;
    private final ReviewJobRepository jobs;
    private final ResultAdmissionService admission;
    private final ReviewAttemptCustody custody;
    private final ReviewCompositionContinuation continuation;
    private final CompositionSessions sessions;
    private final ReviewJobTerminal terminal;
    private final ReviewRunKind reviewRuns;
    private final AgentRuns runs;
    private final TransactionTemplate transactions;
    private final Map<AgentRunFence, Decision> decisions = new ConcurrentHashMap<>();

    public ReviewComposeSandboxKind(
            ReviewJobClaimService claims,
            ReviewJobRepository jobs,
            ResultAdmissionService admission,
            ReviewAttemptCustody custody,
            ReviewCompositionContinuation continuation,
            CompositionSessions sessions,
            ReviewJobTerminal terminal,
            ReviewRunKind reviewRuns,
            AgentRuns runs,
            TransactionTemplate transactions) {
        this.claims = claims;
        this.jobs = jobs;
        this.admission = admission;
        this.custody = custody;
        this.continuation = continuation;
        this.sessions = sessions;
        this.terminal = terminal;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.transactions = transactions;
    }

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.REVIEW_COMPOSE;
    }

    @Override
    public AgentSandboxKind sandbox() {
        return AgentSandboxKind.REVIEW;
    }

    @Override
    public boolean claimed(SandboxRun run) {
        var fence = run.fence();
        return claims.claimLeased(fence)
                .map(started -> {
                    decisions.put(fence, new Decision(started.withholdOnly()));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        var decision = decisions.get(run.fence());
        if (decision == null || decision.withholdOnly() != null) return Optional.empty();
        var fence = run.fence();
        var ref = new AgentRunRef(fence.runId(), fence.workspaceId());
        return jobs.findIdByRun(fence.runId(), fence.workspaceId())
                .map(jobId -> new ProxyAttempt(
                        ProxyAttempt.Caller.REVIEW,
                        jobId,
                        fence.workspaceId(),
                        ReviewRunKind.attempt(fence.epoch()),
                        fence.runId(),
                        runs.find(ref).map(AgentRun::getDeadline).orElse(null)));
    }

    @Override
    public JsonNode context(SandboxRun run) {
        prepare(run);
        return JsonNodeFactory.instance.objectNode();
    }

    /**
     * The run's job, with its inputs held: restored from what its admission retained, or staged empty when they were
     * erased or are gone. The first call of a claim that composes nothing withholds what is open, with the claim's
     * cause or the inputs' loss, so the loop's next session finds nothing open.
     *
     * @throws StaleRunException when the run no longer holds its lease, or this replica lost its claim's decision
     */
    public ResultAdmissionService.Composing prepare(SandboxRun run) {
        var fence = run.fence();
        ResultAdmissionService.Composing composing;
        try {
            composing = admission.composing(fence);
        } catch (ResultAdmissionService.StaleAttemptException stale) {
            throw new StaleRunException(fence, stale);
        }
        var decision = decisions.get(fence);
        if (decision == null) {
            transactions.executeWithoutResult(status -> reviewRuns.requeue(
                    composing.identity().jobId(), fence, AgentRuns.Charge.FREE, Duration.ZERO, null));
            throw new StaleRunException(fence);
        }
        var job = composing.job();
        var identity = composing.identity();
        var cause = decision.withholdOnly();
        if (cause != null) {
            custody.hold(identity, () -> continuation.stageWithoutInputs(job).staged());
            decision.withholdOnce(() -> sessions.withholdOpen(job, identity, cause));
            return composing;
        }
        try {
            custody.hold(identity, () -> continuation.restore(job).staged());
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            var stop = unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE;
            custody.hold(identity, () -> continuation.stageWithoutInputs(job).staged());
            decision.withholdOnce(() -> sessions.withholdOpen(job, identity, stop));
        }
        return composing;
    }

    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        var fence = run.fence();
        ResultAdmissionService.Composing composing;
        try {
            composing = admission.composing(fence);
        } catch (ResultAdmissionService.StaleAttemptException stale) {
            return endedBy(fence);
        }
        UUID jobId = composing.identity().jobId();
        boolean held = switch (outcome) {
            case COMPLETED -> terminal.completeComposed(jobId, fence);
            case HANDED_ON -> reviewRuns.requeue(jobId, fence, AgentRuns.Charge.SPEND, Duration.ZERO, null);
        };
        custody.release(composing.identity());
        decisions.remove(fence);
        return held;
    }

    /** Whether this claim already ended its run, as a repeated end of the same claim finds it. */
    private boolean endedBy(AgentRunFence fence) {
        return runs.find(new AgentRunRef(fence.runId(), fence.workspaceId()))
                .filter(found -> found.getState() == AgentRunState.ENDED && found.getLeaseEpoch() == fence.epoch())
                .isPresent();
    }

    /** What a claim decided: to compose, or to withhold what is open with a cause and make no model call. */
    private static final class Decision {
        private final @Nullable CompositionStop withholdOnly;
        private boolean withheld;

        Decision(@Nullable CompositionStop withholdOnly) {
            this.withholdOnly = withholdOnly;
        }

        @Nullable
        CompositionStop withholdOnly() {
            return withholdOnly;
        }

        /** Withholds what is open once for the claim; a failed withholding is tried again by the next call. */
        synchronized void withholdOnce(Runnable withhold) {
            if (withheld) return;
            withhold.run();
            withheld = true;
        }
    }
}
