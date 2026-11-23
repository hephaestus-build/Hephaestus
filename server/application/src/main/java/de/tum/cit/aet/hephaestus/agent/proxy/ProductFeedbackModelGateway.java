package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The in-process entrance to the same guarded proxy for instance-level report drafts: one call with a system prompt and
 * one user turn, and no tools, so a report's text can make the model answer, never act (OWASP LLM06). The proxy alone
 * writes the request and reads the answer; no other server code speaks a model protocol.
 */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
public class ProductFeedbackModelGateway {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final LlmProxyService proxy;
    private final ObjectMapper mapper;

    /** The draft's text, or why the call gave none. Neither when the model answered with no text. */
    public record Draft(@Nullable String text, @Nullable Failure failure) {}

    public enum Failure {
        /** The action ended while the call waited. */
        ATTEMPT_ENDED,
        /** The instance's AI choice no longer permits the model. */
        AI_CHOICE,
        /** The budget that funds the draft is spent. */
        BUDGET,
        /** The call's deadline has passed. */
        WORK_TIME,
        /** The provider refused the request itself, and it would refuse it again. */
        REFUSED,
        /** The provider failed or answered with something unreadable; another call may succeed. */
        PROVIDER,
        /** The endpoint could not grant capacity in time. */
        NO_CAPACITY
    }

    public Draft draft(
            UUID actionId,
            ProductFeedbackDrafts.Payload draft,
            LlmModelResolver.InstanceModel model,
            Instant deadline,
            ProductFeedbackDraftCalls.DraftCall call) {
        int maxOutputTokens = call.maxOutputTokens();
        byte[] body = mapper.writeValueAsBytes(request(model, maxOutputTokens, call.system(), call.user()));
        var ref = model.ref();
        Long modelLimit = ProxyRouting.outputLimitOf(model.contextWindow(), model.maxOutputTokens());
        long outputLimit = modelLimit == null ? maxOutputTokens : Math.min(modelLimit, maxOutputTokens);
        var routing = new ProxyRouting(
                "product-feedback-draft",
                model.protocol().wire(),
                model.credential().baseUrl(),
                ref.scope(),
                ref.connectionId(),
                ref.modelId(),
                null,
                new ProxyRouting.BilledAttempt(
                        UsageSourceType.PRODUCT_FEEDBACK_DRAFT, draft.feedbackId(), draft.generation(), null),
                deadline,
                outputLimit,
                actionId);
        var response =
                Objects.requireNonNull(proxy.serve(routing, model.protocol(), null, new HttpHeaders(), body, null));
        Object result = response.getBody();
        byte[] bytes = result instanceof byte[] raw
                ? raw
                : result instanceof String text
                        ? text.getBytes(StandardCharsets.UTF_8)
                        : mapper.writeValueAsBytes(result);
        String refusal = response.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER);
        if (refusal != null) return failed(refused(ProxyRefusal.valueOf(refusal)));
        int status = response.getStatusCode().value();
        if (status == 408 || status == 429 || status >= 500) return failed(Failure.PROVIDER);
        // A provider's client error answers the same request the same way again.
        if (status < 200 || status >= 300) return failed(Failure.REFUSED);
        JsonNode answer;
        try {
            answer = mapper.readTree(bytes);
        } catch (JacksonException unreadable) {
            return failed(Failure.PROVIDER);
        }
        return answer == null ? failed(Failure.PROVIDER) : text(model.protocol(), answer);
    }

    private static Failure refused(ProxyRefusal refusal) {
        return switch (refusal) {
            case AI_CHOICE -> Failure.AI_CHOICE;
            case BUDGET -> Failure.BUDGET;
            case WORK_TIME -> Failure.WORK_TIME;
            case ATTEMPT_ENDED -> Failure.ATTEMPT_ENDED;
            // Transient, as a provider's rate limit is.
            case CALLS_IN_FLIGHT -> Failure.PROVIDER;
            case CAPACITY -> Failure.NO_CAPACITY;
            // Only a composition's or a sandbox's request can get these; here they are a fault of this server.
            case NOT_COMPOSER_REQUEST, NOT_STREAMED ->
                throw new IllegalStateException("The proxy refused a report draft with " + refusal);
        };
    }

    /**
     * The request in the model's protocol. It sends no tool field, so the model is offered none. An opaque native
     * model id carries no thinking-mode or sampling contract, so an Anthropic request uses the provider defaults.
     */
    private static ObjectNode request(
            LlmModelResolver.InstanceModel model, int maxOutputTokens, String system, String user) {
        var body = JSON.objectNode().put("model", model.credential().upstreamModelId());
        var turn = JSON.objectNode().put("role", "user").put("content", user);
        var effort = model.reasoningEffort() == null
                ? null
                : model.reasoningEffort().name().toLowerCase(Locale.ROOT);
        switch (model.protocol()) {
            case ANTHROPIC_MESSAGES -> {
                body.put("system", system);
                body.putArray("messages").add(turn);
                body.put("max_tokens", maxOutputTokens);
            }
            case OPENAI_COMPLETIONS -> {
                var messages = body.putArray("messages");
                messages.add(JSON.objectNode().put("role", "system").put("content", system));
                messages.add(turn);
                body.put("store", false);
                body.put("max_completion_tokens", maxOutputTokens);
                if (effort != null) body.put("reasoning_effort", effort);
            }
            case OPENAI_RESPONSES -> {
                body.put("instructions", system);
                body.putArray("input").add(turn);
                body.put("store", false);
                body.put("max_output_tokens", Math.max(16, maxOutputTokens));
                if (effort != null) {
                    body.putObject("reasoning").put("effort", effort);
                    body.putArray("include").add("reasoning.encrypted_content");
                }
            }
        }
        return body;
    }

    private static Draft text(LlmApiProtocol protocol, JsonNode answer) {
        var text = new StringBuilder();
        switch (protocol) {
            case ANTHROPIC_MESSAGES -> {
                if (answer.path("stop_reason").asString("").equals("refusal")) return failed(Failure.REFUSED);
                JsonNode content = answer.path("content");
                if (!content.isArray()) return failed(Failure.PROVIDER);
                for (JsonNode part : content)
                    if (part.path("type").asString("").equals("text"))
                        text.append(part.path("text").asString(""));
            }
            case OPENAI_COMPLETIONS -> {
                JsonNode message = answer.path("choices").path(0).path("message");
                if (!message.isObject()) return failed(Failure.PROVIDER);
                text.append(message.path("content").asString(""));
            }
            case OPENAI_RESPONSES -> {
                JsonNode output = answer.path("output");
                if (!output.isArray()) return failed(Failure.PROVIDER);
                for (JsonNode item : output)
                    if (item.path("type").asString("").equals("message"))
                        for (JsonNode part : item.path("content"))
                            if (part.path("type").asString("").equals("output_text"))
                                text.append(part.path("text").asString(""));
            }
        }
        return new Draft(text.isEmpty() ? null : text.toString(), null);
    }

    private static Draft failed(Failure failure) {
        return new Draft(null, failure);
    }
}
