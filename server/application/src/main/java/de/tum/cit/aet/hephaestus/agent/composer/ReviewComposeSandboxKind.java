package de.tum.cit.aet.hephaestus.agent.composer;

import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionClaim;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobClaimService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobTerminal;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
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
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * A review's composition as a run of its review sandbox (ADR 0079, ADR 0069): the sandbox's composer loop calls the
 * model through the gateway and every composition route, and this kind claims the run, holds its inputs and ends it.
 *
 * <p>The claim records its decision in the job's capture, in the claim transaction. Every replica reads that same
 * decision. Its held input folder is a local cache of the admitted, retained capture.
 */
@Component
@ConditionalOnAgentRuntime
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
    private final ReviewStageTelemetry stages;
    private final ReviewCompositionClaim gateway;

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
            ReviewCompositionClaim gateway,
            MeterRegistry meters,
            Tracer tracer) {
        this.claims = claims;
        this.jobs = jobs;
        this.admission = admission;
        this.custody = custody;
        this.continuation = continuation;
        this.sessions = sessions;
        this.terminal = terminal;
        this.reviewRuns = reviewRuns;
        this.runs = runs;
        this.gateway = gateway;
        this.stages = new ReviewStageTelemetry(meters, tracer);
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
                    gateway.save(
                            run,
                            started.job(),
                            new ReviewCompositionClaim.Decision(Instant.now(), started.withholdOnly(), false));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        var fence = run.fence();
        var ref = new AgentRunRef(fence.runId(), fence.workspaceId());
        return jobs.findByRunIdAndWorkspaceId(fence.runId(), fence.workspaceId())
                .flatMap(job -> gateway.find(run, job)
                        .filter(decision -> decision.withholdOnly() == null)
                        .map(decision -> new ProxyAttempt(
                                ProxyAttempt.Caller.REVIEW,
                                job.getId(),
                                fence.workspaceId(),
                                fence.epoch(),
                                fence.runId(),
                                runs.find(ref)
                                        .map(AgentRun::getDeadline)
                                        .map(ReviewRunKind::compositionWorkDeadline)
                                        .orElse(null))));
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
     * @throws StaleRunException when the run no longer holds its lease
     */
    public ResultAdmissionService.Composing prepare(SandboxRun run) {
        var fence = run.fence();
        ResultAdmissionService.Composing composing;
        try {
            composing = admission.composing(fence);
        } catch (ResultAdmissionService.StaleAttemptException stale) {
            throw new StaleRunException(fence, stale);
        }
        var job = composing.job();
        var decision = gateway.require(run, job);
        var identity = composing.identity();
        var cause = decision.withholdOnly();
        if (cause != null) {
            custody.hold(identity, () -> continuation.stageWithoutInputs(job).staged());
            if (!decision.withheld()) gateway.withholdOnce(run, job, () -> sessions.withholdOpen(job, identity, cause));
            return composing;
        }
        try {
            custody.hold(identity, () -> continuation.restore(job).staged());
        } catch (ReviewCompositionContinuation.InputsUnavailableException unavailable) {
            var stop = unavailable.erased() ? CompositionStop.INPUTS_ERASED : CompositionStop.INPUTS_GONE;
            custody.hold(identity, () -> continuation.stageWithoutInputs(job).staged());
            if (!decision.withheld()) gateway.withholdOnce(run, job, () -> sessions.withholdOpen(job, identity, stop));
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
        var previous = MDC.getCopyOfContextMap();
        MDC.put(StructuredLogKeys.JOB_ID, jobId.toString());
        try {
            var decision = gateway.find(run, composing.job());
            boolean held = switch (outcome) {
                case COMPLETED -> terminal.completeComposed(jobId, fence);
                case HANDED_ON -> reviewRuns.retryComposition(jobId, fence);
            };
            custody.release(composing.identity());
            if (held && decision.isPresent())
                stages.record(
                        ReviewStageTelemetry.Stage.COMPOSITION,
                        Duration.between(decision.get().started(), Instant.now()),
                        outcome == Outcome.COMPLETED
                                ? ReviewStageTelemetry.Outcome.OK
                                : ReviewStageTelemetry.Outcome.HANDED_ON);
            return held;
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }

    /** Whether this claim already ended its run, as a repeated end of the same claim finds it. */
    private boolean endedBy(AgentRunFence fence) {
        return runs.find(new AgentRunRef(fence.runId(), fence.workspaceId()))
                .filter(found -> found.getState() == AgentRunState.ENDED && found.getLeaseEpoch() == fence.epoch())
                .isPresent();
    }
}
