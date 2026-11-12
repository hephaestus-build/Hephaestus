package de.tum.cit.aet.hephaestus.agent.productfeedback;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.composer.ModelSession;
import de.tum.cit.aet.hephaestus.agent.proxy.ProductFeedbackModelGateway;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.action.ActionAdapter;
import de.tum.cit.aet.hephaestus.core.action.ActionAttempt;
import de.tum.cit.aet.hephaestus.core.action.ActionEnded;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionKind;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Drafts a product-feedback report ({@link ActionKind#DRAFT_REPORT}): one call to the operator's instance model
 * through the LLM proxy, in the lowest model-call class. The call offers the model no tools, so a report's text can
 * make the model answer, never act (OWASP LLM06). It writes nothing outside, so it needs no fence and no readback: a
 * repeated call costs tokens only. Product feedback validates and routes the answer; this adapter only carries it.
 */
@Component
@ConditionalOnServerRole
@WorkspaceAgnostic("Product feedback drafting uses the instance's model; it belongs to no workspace")
class DraftReportAdapter implements ActionAdapter {
    /**
     * The call ends before the action's lease does, with time left to record its result, so a reclaim after a lost
     * lease cannot overlap it.
     */
    static final Duration CALL_TIME = ActionExecutor.LEASE.minusSeconds(20);

    private final ProductFeedbackDrafts drafts;
    private final ProductFeedbackDraftCalls calls;
    private final LlmModelResolver models;
    private final Optional<ProductFeedbackModelGateway> gateway;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final LlmUsageRecorder usage;

    DraftReportAdapter(
            ProductFeedbackDrafts drafts,
            ProductFeedbackDraftCalls calls,
            LlmModelResolver models,
            Optional<ProductFeedbackModelGateway> gateway,
            ObjectMapper mapper,
            Clock clock,
            LlmUsageRecorder usage) {
        this.usage = usage;
        this.drafts = drafts;
        this.calls = calls;
        this.models = models;
        this.gateway = gateway;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.DRAFT_REPORT;
    }

    @Override
    public ActionResult execute(ActionAttempt attempt) {
        Instant deadline = clock.instant().plus(CALL_TIME);
        if (gateway.isEmpty()) return new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED);
        var payload = mapper.treeToValue(attempt.payload(), ProductFeedbackDrafts.Payload.class);
        ProductFeedbackDraftCalls.DraftCall call =
                calls.waiting(payload.feedbackId(), payload.generation()).orElse(null);
        // The report was erased, edited again or moved on: nothing waits for this draft.
        if (call == null) return new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        LlmModelResolver.InstanceModel model = models.resolveInstanceModel(call.llmModelId());
        if (model == null) return new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED);
        if (!drafts.claimed(attempt, payload.feedbackId(), payload.generation())) return new ActionResult.Abandoned();
        var settings = new ModelSession.Settings(
                model.protocol(),
                model.credential().upstreamModelId(),
                model.reasoningEffort(),
                call.maxOutputTokens(),
                null);
        ModelSession.Transport transport = body -> {
            var answer =
                    gateway.orElseThrow().draft(attempt.id(), payload, model, deadline, call.maxOutputTokens(), body);
            return new ModelSession.Answer(answer.status(), answer.body(), answer.owned(), answer.refusal());
        };
        ModelSession.Reply reply =
                new ModelSession(transport, mapper, settings, call.system(), List.of()).send(call.user());
        String text = reply.text();
        ModelSession.Failure failure = reply.failure();
        if (failure == null && text != null)
            return drafts.answered(attempt, payload.feedbackId(), payload.generation(), text)
                    ? new ActionResult.Done(null)
                    : new ActionResult.Abandoned();
        if (failure == null) return new ActionResult.Retry(ActionExecutor.backoff(attempt.attempt()));
        return switch (failure) {
            // The provider or the pool failed this time; another call may succeed.
            case PROVIDER, WORK_TIME -> new ActionResult.Retry(ActionExecutor.backoff(attempt.attempt()));
            case NO_CAPACITY ->
                drafts.deferred(attempt, payload.feedbackId(), payload.generation())
                        ? new ActionResult.Defer(Duration.ofSeconds(30))
                        : new ActionResult.Abandoned();
            // The destination names another model now.
            case AI_CHOICE, BUDGET -> new ActionResult.Failed(ActionStopReason.NOT_CONFIGURED);
            // The report no longer waits for this draft.
            case OWNERSHIP_LOST -> new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
            // The provider refused this request, and it would refuse it again.
            case REFUSED -> new ActionResult.Failed(ActionStopReason.REJECTED);
        };
    }

    @Override
    public void ended(ActionEnded ended) {
        drafts.ended(ended);
        usage.settleAction(ended.id());
    }
}
