package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyAttempt;
import de.tum.cit.aet.hephaestus.agent.proxy.ProxyRouting;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStopReason;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnAgentRuntime
@Profile("!specs & !cds-training")
@RequiredArgsConstructor
class DraftReportSandboxRun implements SandboxRunKind, ProductFeedbackDrafts.Queue {
    private static final Logger log = LoggerFactory.getLogger(DraftReportSandboxRun.class);

    private final AgentRuns runs;
    private final DraftReportRuns lifecycle;
    private final ProductFeedbackDrafts drafts;
    private final ProductFeedbackDraftCalls calls;
    private final LlmModelResolver models;
    private final ObjectMapper mapper;
    private final LlmUsageRecorder usage;

    @Override
    public AgentRunKind kind() {
        return AgentRunKind.DRAFT_REPORT;
    }

    @Override
    public AgentSandboxKind sandbox() {
        return AgentSandboxKind.DRAFT_REPORT;
    }

    @Override
    public void enqueue(ProductFeedbackDrafts.Subject subject, Duration deadline) {
        runs.enqueueReportDraft(subject.key(), deadline);
    }

    @Override
    public void beforeWrite(AgentSandboxes.LiveSandbox sandbox) {
        drafts.lock(ProductFeedbackDrafts.Subject.fromKey(sandbox.subjectKey()));
    }

    @Override
    public boolean claimed(SandboxRun run) {
        var subject = subject(run);
        if (drafts.claimed(subject)) return true;
        runs.end(run.fence(), AgentRunStopReason.CANCELLED);
        return false;
    }

    @Override
    public Optional<ProxyAttempt> attempt(SandboxRun run) {
        var subject = subject(run);
        return runs.find(run.fence().run())
                .flatMap(found -> Optional.ofNullable(found.getDeadline()))
                .map(deadline -> new ProxyAttempt(
                        ProxyAttempt.Caller.DRAFT_REPORT,
                        subject.feedbackId(),
                        AgentRunRef.INSTANCE_SCOPE,
                        run.fence().epoch(),
                        run.fence().runId(),
                        deadline,
                        subject.generation()));
    }

    @Override
    public JsonNode context(SandboxRun run) {
        var subject = subject(run);
        var draft = calls.waiting(subject.feedbackId(), subject.generation()).orElse(null);
        if (draft == null) return mapper.createObjectNode();
        var model = models.resolveInstanceModel(draft.llmModelId());
        if (model == null) return mapper.createObjectNode();
        var configured = mapper.createObjectNode()
                .put("modelId", model.credential().upstreamModelId())
                .put("apiProtocol", model.protocol().wire())
                .put("maxOutputTokens", ProxyRouting.draftOutputLimit(model, draft.maxOutputTokens()));
        if (model.contextWindow() != null) configured.put("contextWindow", model.contextWindow());
        if (model.reasoningEffort() != null)
            configured.put("reasoningEffort", model.reasoningEffort().name());
        return mapper.createObjectNode()
                .put("instanceModelId", Long.toString(draft.llmModelId()))
                .put("system", draft.system())
                .put("prompt", draft.user())
                .set("model", configured);
    }

    @Override
    public boolean end(SandboxRun run, Outcome outcome) {
        return end(run, outcome, null, null);
    }

    @Override
    public boolean end(SandboxRun run, Outcome outcome, @Nullable String cause, @Nullable String answer) {
        if (runs.find(run.fence().run())
                .filter(found -> found.getState() == AgentRunState.ENDED
                        && found.getLeaseEpoch() == run.fence().epoch())
                .isPresent()) return true;
        var subject = subject(run);
        boolean waiting = calls.waits(subject.feedbackId(), subject.generation());
        if (waiting && outcome == Outcome.HANDED_ON && "model_capacity".equals(cause)) {
            if (!runs.defer(run.fence(), AgentRuns.Deferral.MODEL_POOL)) return false;
            drafts.deferred(subject);
            return true;
        }
        if (waiting && outcome == Outcome.HANDED_ON && run.attempt() < lifecycle.retries()) {
            return runs.requeue(run.fence(), AgentRuns.Charge.SPEND, AgentRuns.backoff(run.attempt() + 1), null);
        }
        AgentRunStopReason reason;
        String detail;
        if (!waiting) {
            reason = AgentRunStopReason.CANCELLED;
            detail = "cancelled";
        } else if (outcome == Outcome.HANDED_ON) {
            reason = AgentRunStopReason.FAILED;
            detail = "model_unavailable".equals(cause) ? "model_unavailable" : "sandbox_unavailable";
        } else if (answer != null
                && !answer.isBlank()
                && usage.hasReportDraftCall(run.fence().runId(), run.fence().epoch())) {
            reason = AgentRunStopReason.COMPLETED;
            detail = "completed";
        } else if ("model_timeout".equals(cause)) {
            reason = AgentRunStopReason.DEADLINE;
            detail = "model_timeout";
        } else {
            reason = AgentRunStopReason.FAILED;
            detail = "invalid_context".equals(cause) ? "invalid_context" : "model_failed";
        }
        var ended = runs.end(run.fence(), reason);
        if (ended == AgentRuns.EndOutcome.ENDED) {
            drafts.ended(subject, reason == AgentRunStopReason.COMPLETED ? answer : null, detail);
            log.atInfo()
                    .addKeyValue("event.name", "agent.run.ended")
                    .addKeyValue(StructuredLogKeys.RUN_ID, run.fence().runId())
                    .addKeyValue(StructuredLogKeys.RUN_EPOCH, run.fence().epoch())
                    .addKeyValue("run.kind", kind())
                    .addKeyValue("run.stop_reason", reason)
                    .addKeyValue("run.detail", detail)
                    .log("Report draft run ended");
        }
        return ended != AgentRuns.EndOutcome.STALE;
    }

    private static ProductFeedbackDrafts.Subject subject(SandboxRun run) {
        return ProductFeedbackDrafts.Subject.fromKey(run.sandbox().subjectKey());
    }
}
