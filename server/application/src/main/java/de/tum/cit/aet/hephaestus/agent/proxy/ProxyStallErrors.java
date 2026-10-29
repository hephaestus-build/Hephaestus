package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.StreamOutcome;
import de.tum.cit.aet.hephaestus.core.proxy.ProxyStreamingUtils.UpstreamResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** How {@link LlmProxyService} reports a provider's failure: to the connection's breaker, and to the model client. */
final class ProxyStallErrors {

    private static final Logger log = LoggerFactory.getLogger(ProxyStallErrors.class);

    private final ObjectMapper objectMapper;

    ProxyStallErrors(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Tells the connection's breaker how the provider did: a stall, a broken stream or a server error counts against
     * it; a request error or a client that went away says nothing about the provider.
     */
    static void judge(UpstreamResult upstream, LlmConnectionHealth.Permit permit) {
        StreamOutcome outcome = upstream.streamOutcome();
        if (upstream.status() >= 500) {
            permit.failed(new IllegalStateException("upstream status " + upstream.status()));
        } else if (outcome == StreamOutcome.UPSTREAM_IDLE || outcome == StreamOutcome.STREAM_FAILED) {
            permit.failed(new IllegalStateException("upstream stream " + outcome));
        } else if (upstream.status() >= 400 || outcome == StreamOutcome.CLIENT_DISCONNECTED) {
            permit.released();
        } else {
            permit.succeeded();
        }
    }

    /**
     * An error a model client reads as the provider's: a JSON body with {@code error}, whose message starts with the
     * code. The Heph host recognizes the codes ({@code heph-host.ts}).
     */
    ResponseEntity<String> providerError(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(errorPayload(code, message)));
    }

    ObjectNode errorPayload(String code, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("error")
                .put("message", code + ": " + message)
                .put("type", code)
                .put("code", code);
        return payload;
    }

    /**
     * Ends a stream whose provider went silent with an error event. The response headers are already sent, and a
     * stream that just stops would read as a broken one. The event name and envelope match the selected protocol.
     */
    void writeStallFrame(HttpServletResponse response, LlmApiProtocol protocol, String message) {
        writeErrorFrame(response, protocol, LlmProxyService.UPSTREAM_STALLED, message);
    }

    /** Ends a stream the proxy stopped with an error event, so a client cannot read the cut answer as complete. */
    void writeErrorFrame(HttpServletResponse response, LlmApiProtocol protocol, String code, String message) {
        try {
            OutputStream out = response.getOutputStream();
            var payload = errorPayload(code, message);
            String event = "";
            if (protocol == LlmApiProtocol.ANTHROPIC_MESSAGES) {
                event = "event: error\n";
                payload.put("type", "error");
            }
            out.write((event + "data: " + objectMapper.writeValueAsString(payload) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException clientGone) {
            log.debug("The client left before the stall was reported");
        }
    }
}
