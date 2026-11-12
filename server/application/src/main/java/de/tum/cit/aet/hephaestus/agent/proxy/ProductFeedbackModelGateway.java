package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDrafts;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** The in-process entrance to the same guarded proxy for instance-level report drafts. */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
public class ProductFeedbackModelGateway {
    private final LlmProxyService proxy;
    private final ObjectMapper mapper;

    @SuppressWarnings("ArrayRecordComponent") // Response bytes are parsed, never compared or hashed.
    public record Answer(
            int status,
            byte[] body,
            boolean owned,
            @Nullable ProxyRefusal refusal) {}

    public Answer draft(
            UUID actionId,
            ProductFeedbackDrafts.Payload draft,
            LlmModelResolver.InstanceModel model,
            Instant deadline,
            int maxOutputTokens,
            byte[] body) {
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
        var response = Objects.requireNonNull(proxy.serve(routing, model.protocol(), null, new HttpHeaders(), body));
        Object result = response.getBody();
        byte[] bytes = result instanceof byte[] raw
                ? raw
                : result instanceof String text
                        ? text.getBytes(StandardCharsets.UTF_8)
                        : mapper.writeValueAsBytes(result);
        String refusal = response.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER);
        return new Answer(
                response.getStatusCode().value(), bytes, true, refusal == null ? null : ProxyRefusal.valueOf(refusal));
    }
}
