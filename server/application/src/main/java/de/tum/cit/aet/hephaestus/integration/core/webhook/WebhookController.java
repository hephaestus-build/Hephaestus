package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified webhook ingest controller: {@code POST /webhooks/{kind}} resolves the
 * {@link IntegrationKindRouting kind} from the path and delegates to
 * {@link WebhookIngestPipeline}. GitHub and the Slack Events API share this endpoint;
 * GitLab and Outline deliver only to their connection's own route. Slack interactivity stays on {@code /webhooks/slack/interactivity} because
 * Slack configures it as a separate Request URL, and it is served by the server role, which
 * commits the consent change before the ACK; the edge routes that one path there.
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
                .orElseGet(
                        () -> WebhookIngestPipeline.problem(HttpStatus.NOT_FOUND, "unknown integration kind: " + kind));
    }

    private ResponseEntity<?> doHandle(IntegrationKind k, HttpServletRequest req) {
        try {
            return pipeline.handle(k, req);
        } catch (IOException e) {
            return WebhookIngestPipeline.problem(HttpStatus.BAD_REQUEST, "could not read body");
        }
    }
}
