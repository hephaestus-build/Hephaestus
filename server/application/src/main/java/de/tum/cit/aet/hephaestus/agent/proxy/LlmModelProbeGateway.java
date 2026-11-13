package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnAgentRuntime;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Synthetic model checks use the same egress, capacity, output limit and accounting as other model calls. */
@Component
@ConditionalOnAgentRuntime
@RequiredArgsConstructor
public class LlmModelProbeGateway {
    public static final int MAX_OUTPUT_TOKENS = 64;
    static final Duration CALL_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final LlmProxyService proxy;
    private final ObjectMapper mapper;

    public record Answer(int status, String body) {}

    /** The probe id groups the baseline and forced-choice calls; it names no workspace, run or action. */
    public Answer call(UUID probeId, LlmModelResolver.ConnectionRef model, LlmApiProtocol protocol, ObjectNode body) {
        var route = new ProxyRouting(
                "model-capability-probe",
                protocol.wire(),
                "",
                model.scope(),
                model.connectionId(),
                model.modelId(),
                model.workspaceId(),
                new ProxyRouting.BilledAttempt(UsageSourceType.PROBE, probeId, 0, null, model.workspaceId()),
                Instant.now().plus(CALL_TIMEOUT),
                (long) MAX_OUTPUT_TOKENS);
        var previous = MDC.getCopyOfContextMap();
        try {
            // A synthetic call has no run or review, including when an admin request inherited their context.
            MDC.remove(StructuredLogKeys.RUN_ID);
            MDC.remove(StructuredLogKeys.RUN_EPOCH);
            MDC.remove(StructuredLogKeys.SUBJECT_KEY);
            MDC.remove(StructuredLogKeys.REVIEW_ID);
            MDC.remove(StructuredLogKeys.JOB_ID);
            MDC.remove(StructuredLogKeys.JOB_ATTEMPT);
            if (model.workspaceId() == null) MDC.remove(StructuredLogKeys.WORKSPACE_ID);
            else MDC.put(StructuredLogKeys.WORKSPACE_ID, model.workspaceId().toString());
            var response = Objects.requireNonNull(
                    proxy.serve(route, protocol, null, new HttpHeaders(), mapper.writeValueAsBytes(body)));
            Object result = response.getBody();
            byte[] bytes = result instanceof byte[] raw
                    ? raw
                    : result instanceof String text
                            ? text.getBytes(StandardCharsets.UTF_8)
                            : mapper.writeValueAsBytes(result);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("Probe response too large");
            return new Answer(response.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
