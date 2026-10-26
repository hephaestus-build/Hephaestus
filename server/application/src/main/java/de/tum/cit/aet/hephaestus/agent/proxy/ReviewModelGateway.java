package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWorkerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * The governed model path for a step the worker runs for a review attempt, such as feedback composition: each call
 * is registered, priced, budgeted and fenced on the attempt exactly as the attempt's sandbox calls are.
 */
@Service
@ConditionalOnWorkerRole
public class ReviewModelGateway {
    private final ProxyRoutingService routing;
    private final LlmProxyService proxy;
    private final ObjectMapper mapper;

    ReviewModelGateway(ProxyRoutingService routing, LlmProxyService proxy, ObjectMapper mapper) {
        this.routing = routing;
        this.proxy = proxy;
        this.mapper = mapper;
    }

    /** The attempt a call bills to, and the end of the work time it may use. */
    public record Attempt(UUID jobId, long workspaceId, int attempt, String workerId, Instant deadline) {}

    /** Which of the proxy's own rules refused a call. */
    public enum Refusal {
        /** The workspace's AI choice no longer permits the model. */
        AI_CHOICE,
        /** The budget that funds the call is spent. */
        BUDGET,
        /** The attempt's work deadline has passed. */
        WORK_TIME,
        /** The attempt was ended while the call waited. */
        ATTEMPT_ENDED
    }

    /**
     * One answer: the provider's status and body, or the proxy's refusal.
     *
     * @param owned false when the attempt no longer runs on this worker, so no call started
     * @param refusal the proxy rule that refused the call, or null when the provider answered
     */
    @SuppressWarnings("ArrayRecordComponent") // the provider's response bytes, parsed once by the caller
    public record Answer(
            int status,
            byte[] body,
            boolean owned,
            @Nullable Refusal refusal) {
        public boolean served() {
            return status >= 200 && status < 300;
        }
    }

    /** Sends one non-streaming chat call, built by the worker, in the attempt's frozen protocol and model. */
    public Answer call(Attempt attempt, byte[] body) {
        ProxyRouting route;
        try {
            route = routing.workerRouting(
                    attempt.jobId(), attempt.workspaceId(), attempt.attempt(), attempt.workerId(), attempt.deadline());
        } catch (ResponseStatusException unavailable) {
            return new Answer(unavailable.getStatusCode().value(), new byte[0], false, null);
        }
        LlmApiProtocol protocol = LlmApiProtocol.parse(route.apiProtocol())
                .filter(parsed -> parsed.serves(ModelKind.CHAT))
                .orElse(null);
        if (protocol == null) return new Answer(HttpStatus.NOT_FOUND.value(), new byte[0], true, null);
        ResponseEntity<?> answer =
                Objects.requireNonNull(proxy.serve(route, protocol, null, null, new HttpHeaders(), body));
        Object payload = answer.getBody();
        byte[] bytes = switch (payload) {
            case null -> new byte[0];
            case byte[] raw -> raw;
            case String text -> text.getBytes(StandardCharsets.UTF_8);
            default -> mapper.writeValueAsBytes(payload);
        };
        String rule = answer.getHeaders().getFirst(LlmProxyService.REFUSAL_HEADER);
        return new Answer(answer.getStatusCode().value(), bytes, true, rule == null ? null : Refusal.valueOf(rule));
    }
}
