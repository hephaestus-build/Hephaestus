package de.tum.cit.aet.hephaestus.integration.outline.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnWebhookRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.VerificationResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.WebhookSignatureVerifier.WebhookRequest;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookIngestPipeline;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives an Outline change notification on the route its subscription was registered with. The signing secret is
 * derived from the route ({@link OutlineRouteCredential}), so nothing is read from the database. A verified delivery
 * is published with its route in {@link #HEADER_ROUTE}; the server drops it unless the subscription the body names
 * registered that route.
 *
 * <p>Active only on the webhook runtime role.
 */
@Hidden
@RestController
@ConditionalOnWebhookRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineWebhookController {

    public static final String PATH_PREFIX = "/webhooks/outline/";

    /** The NATS header the receiver writes from the verified route ({@code keyId/routeId}). */
    public static final String HEADER_ROUTE = "Hephaestus-Route";

    private static final Logger log = LoggerFactory.getLogger(OutlineWebhookController.class);

    private final WebhookIngestPipeline pipeline;
    private final OutlineRouteCredential credential;
    private final OutlineWebhookSignatureVerifier verifier;
    private final OutlineSubjectKeyDeriver deriver;

    public OutlineWebhookController(
            WebhookIngestPipeline pipeline,
            OutlineRouteCredential credential,
            OutlineWebhookSignatureVerifier verifier,
            OutlineSubjectKeyDeriver deriver) {
        this.pipeline = pipeline;
        this.credential = credential;
        this.verifier = verifier;
        this.deriver = deriver;
    }

    @PostMapping(PATH_PREFIX + "{keyId}/{routeId}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> ingest(@PathVariable String keyId, @PathVariable String routeId, HttpServletRequest req)
            throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        Map<String, String> headers = WebhookIngestPipeline.readHeaders(req);
        Optional<byte[]> secret = credential.signingSecret(keyId, routeId);
        if (secret.isEmpty()) {
            log.warn("Rejected Outline webhook: reason=unknownRoute");
            return WebhookIngestPipeline.problem(HttpStatus.UNAUTHORIZED, "invalid");
        }
        VerificationResult result = verifier.verify(new WebhookRequest(body, headers), secret.get());
        if (!(result instanceof VerificationResult.Verified)) {
            log.warn("Rejected Outline webhook: reason={}", result.getClass().getSimpleName());
            return WebhookIngestPipeline.problem(HttpStatus.UNAUTHORIZED, "invalid");
        }
        return pipeline.publishAuthenticated(
                IntegrationKind.OUTLINE,
                body,
                headers,
                payload -> deriver.deriveSubject(payload, headers),
                deriver.deriveDedupKey(body, headers),
                Map.of(HEADER_ROUTE, keyId + "/" + routeId));
    }
}
