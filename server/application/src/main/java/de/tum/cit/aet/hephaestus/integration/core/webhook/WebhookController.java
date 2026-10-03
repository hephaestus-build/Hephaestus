package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified webhook ingest controller: {@code POST /webhooks/{kind}} resolves the
 * {@link IntegrationKindRouting kind} from the path and delegates to
 * {@link WebhookIngestPipeline}. GitHub, GitLab and Slack Events API all share this
 * endpoint. Slack interactivity stays on {@code /webhooks/slack/interactivity} because
 * Slack configures it as a separate Request URL and it needs a fast ACK before postback
 * work runs.
 *
 * <p>Active only on the webhook runtime role.
 */
@RestController
@ConditionalOnWebhookRole
public class WebhookController {

    private final WebhookIngestPipeline pipeline;
    private final IntegrationKindRouting routing;

    public WebhookController(WebhookIngestPipeline pipeline, IntegrationKindRouting routing) {
        this.pipeline = pipeline;
        this.routing = routing;
    }

    @PostMapping("/webhooks/{kind}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> ingest(@PathVariable String kind, HttpServletRequest req) throws IOException {
        return routing.resolve(kind)
                .<ResponseEntity<?>>map(k -> doHandle(k, req))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "unknown integration kind: " + kind)));
    }

    private ResponseEntity<?> doHandle(IntegrationKind k, HttpServletRequest req) {
        try {
            return pipeline.handle(k, req);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "could not read body: " + e.getMessage()));
        }
    }
}
