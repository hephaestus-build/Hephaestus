package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackSignatureVerifier;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Slack Interactivity Request URL. Consent state changes and erasure complete before Slack is acknowledged; the
 * remote follow-ups (confirmation message, App Home refresh) run asynchronously so the 3-second ack budget never
 * depends on Slack API latency.
 */
@Hidden // inbound Slack webhook receiver — not part of the webapp API surface; excluded from the OpenAPI client
@RestController
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Slack interactivity; the workspace is resolved from the payload's team_id, not the URL")
public class SlackInteractivityController {

    private static final Logger log = LoggerFactory.getLogger(SlackInteractivityController.class);

    private final SlackSignatureVerifier verifier;
    private final SlackInteractivityHandler handler;
    private final ObjectMapper objectMapper;

    public SlackInteractivityController(
            SlackSignatureVerifier verifier, SlackInteractivityHandler handler, ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/webhooks/slack/interactivity")
    @PreAuthorize("permitAll()")
    public ResponseEntity<String> interactivity(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request) {
        return interactivity(rawBodyFrom(request, rawBody), headers);
    }

    ResponseEntity<String> interactivity(byte[] rawBody, HttpHeaders headers) {
        if (rawBody == null) {
            return ResponseEntity.badRequest().build();
        }
        String timestamp = headers.getFirst("X-Slack-Request-Timestamp");
        String signature = headers.getFirst("X-Slack-Signature");
        SlackSignatureVerifier.Verification verification =
                verifier.check(timestamp, signature, rawBody, Instant.now().getEpochSecond());
        if (verification.status() != SlackSignatureVerifier.Verification.Status.VALID) {
            log.warn(
                    "Slack interactivity rejected: status={}, driftSeconds={}",
                    verification.status(),
                    verification.driftSeconds());
            return ResponseEntity.status(401).build();
        }

        String payloadJson = extractPayload(new String(rawBody, StandardCharsets.UTF_8));
        if (payloadJson.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        final JsonNode payload;
        try {
            payload = objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        String type = payload.path("type").asString("");
        try {
            dispatch(type, payload);
        } catch (RuntimeException e) {
            log.warn("slack.interactivity: failed to handle payload type {}: {}", type, e.toString());
            return ResponseEntity.status(500).build();
        }
        return ResponseEntity.ok().build();
    }

    private static byte[] rawBodyFrom(HttpServletRequest request, byte[] fallback) {
        Object rawBody = request.getAttribute(SlackInteractivityRawBodyFilter.RAW_BODY_ATTRIBUTE);
        if (rawBody instanceof byte[] bytes && bytes.length > 0) {
            return bytes;
        }
        return fallback;
    }

    private void dispatch(String type, JsonNode payload) {
        switch (type) {
            case "block_actions" -> handler.handleBlockActions(payload);
            default -> log.debug("slack.interactivity: ignoring payload type {}", type);
        }
    }

    static String extractPayload(String body) {
        for (String pair : body.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq > 0 && "payload".equals(pair.substring(0, eq))) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
