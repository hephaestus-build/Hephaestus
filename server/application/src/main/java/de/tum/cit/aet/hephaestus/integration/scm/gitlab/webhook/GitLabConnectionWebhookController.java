package de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookIngestPipeline;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
 * Receives a connection-scoped GitLab group hook. The hook's {@code X-Gitlab-Token} must be a
 * {@link GitLabRouteCredential} for exactly the connection, key and route named in the URL; nothing is read from the
 * database, so a verified delivery is published durably before any admission decision. {@code X-Gitlab-Instance} is
 * compared with the signed origin only to turn away a mismatch: it never selects a route.
 *
 * <p>Active only on the webhook runtime role.
 */
@RestController
@ConditionalOnProperty(name = RuntimeRole.WEBHOOK_PROPERTY, havingValue = "true", matchIfMissing = true)
public class GitLabConnectionWebhookController {

    public static final String PATH_PREFIX = "/webhooks/gitlab/connections/";

    private static final Logger log = LoggerFactory.getLogger(GitLabConnectionWebhookController.class);

    private final WebhookIngestPipeline pipeline;
    private final GitLabRouteCredential credential;
    private final GitlabSubjectKeyDeriver deriver;

    public GitLabConnectionWebhookController(
            WebhookIngestPipeline pipeline, GitLabRouteCredential credential, GitlabSubjectKeyDeriver deriver) {
        this.pipeline = pipeline;
        this.credential = credential;
        this.deriver = deriver;
    }

    @PostMapping(PATH_PREFIX + "{connectionId}/{keyId}/{routeId}")
    @PreAuthorize("permitAll()")
    public ResponseEntity<?> ingest(
            @PathVariable long connectionId,
            @PathVariable String keyId,
            @PathVariable String routeId,
            HttpServletRequest req)
            throws IOException {
        byte[] body = req.getInputStream().readAllBytes();
        Map<String, String> headers = WebhookIngestPipeline.readHeaders(req);
        Optional<GitLabRouteCredential.Route> route =
                credential.verify(header(headers, "x-gitlab-token"), connectionId, keyId, routeId);
        if (route.isEmpty()) {
            log.warn("Rejected GitLab connection webhook: reason=invalidCredential, connectionId={}", connectionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
        }
        String instance = header(headers, "x-gitlab-instance");
        if (instance != null
                && !ScmOrigin.of(instance).equals(Optional.of(route.get().providerOrigin()))) {
            log.warn("Rejected GitLab connection webhook: reason=instanceMismatch, connectionId={}", connectionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid"));
        }
        return pipeline.publishAuthenticated(
                IntegrationKind.GITLAB,
                body,
                headers,
                payload -> deriver.deriveConnectionSubject(connectionId, payload),
                deriver.deriveConnectionDedupKey(connectionId, body, headers),
                route.get().headers());
    }

    private static @Nullable String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null
                    && entry.getKey().toLowerCase(Locale.ROOT).equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
