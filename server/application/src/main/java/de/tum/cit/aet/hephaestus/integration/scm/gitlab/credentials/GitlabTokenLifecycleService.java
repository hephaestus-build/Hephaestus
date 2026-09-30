package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabTokenMetadata;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Inspection and irreversible rotation share the connection lifecycle lock with replacement. */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitlabTokenLifecycleService {
    private final ConnectionService connections;
    private final ConnectionRepository repository;
    private final ObjectProvider<GitLabTokenRotationClient> rotationClients;
    private final ObjectProvider<GitLabTokenService> tokenServices;
    private final GitlabCredentialHealth health;
    private final WebhookProperties properties;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    @Transactional
    public void check(long workspaceId) {
        var client = rotationClients.getIfAvailable();
        if (client == null) return;
        var candidate = connections.findActive(workspaceId, IntegrationKind.GITLAB);
        if (candidate.isEmpty()) return;
        var connection = candidate.get();
        repository.acquireLifecycleLock(connection.getId(), workspaceId);
        Objects.requireNonNull(entityManager).refresh(connection);
        if (connection.getState() != IntegrationState.ACTIVE
                || !(connection.getConfig() instanceof GitLabConfig config)) return;
        var bearer = connections.findActiveBearerToken(workspaceId, IntegrationKind.GITLAB);
        if (bearer.isEmpty()) return;
        String token = bearer.get().token();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int threshold = properties.tokenRotation().thresholdDays();
        boolean expiringObserved = config.tokenMetadata() != null
                && config.tokenMetadata().expiresAt() != null
                && !config.tokenMetadata().expiresAt().isAfter(today.plusDays(Math.max(0, threshold)));
        boolean rotationAttempted = false;
        try {
            var info = client.getTokenInfo(workspaceId);
            connection.setConfig(config.withTokenMetadata(new GitLabTokenMetadata(info.expiresAt(), clock.instant())));
            health.observe(workspaceId, token, false);
            boolean expiring =
                    info.expiresAt() != null && !info.expiresAt().isAfter(today.plusDays(Math.max(0, threshold)));
            expiringObserved = expiring;
            if (!expiring) {
                health.expiring(workspaceId, false);
                return;
            }
            if (threshold <= 0) {
                health.expiring(workspaceId, true);
                return;
            }
            // No retries: a lost response may already have revoked the old token. Keep the row locked
            // across this bounded control-plane call so another rotation or replacement cannot race it.
            rotationAttempted = true;
            var rotated = client.rotateToken(
                    workspaceId, today.plusDays(properties.tokenRotation().validityDays()));
            connections
                    .rotateBearerToken(
                            workspaceId,
                            IntegrationKind.GITLAB,
                            new BearerToken(
                                    rotated.token(),
                                    rotated.expiresAt().atStartOfDay().toInstant(ZoneOffset.UTC)))
                    .orElseThrow();
            var currentConfig = (GitLabConfig) connection.getConfig();
            connection.setConfig(
                    currentConfig.withTokenMetadata(new GitLabTokenMetadata(rotated.expiresAt(), clock.instant())));
            health.expiring(workspaceId, false);
            log.info("Rotated GitLab token: workspaceId={}, expiresAt={}", workspaceId, rotated.expiresAt());
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 401) {
                health.observe(workspaceId, token, true);
            } else if (rotationAttempted || expiringObserved) {
                health.expiring(workspaceId, true);
            }
            log.warn(
                    "GitLab token inspection or rotation failed: workspaceId={}, status={}",
                    workspaceId,
                    e.getStatusCode().value());
        } catch (RuntimeException e) {
            if (rotationAttempted || expiringObserved) health.expiring(workspaceId, true);
            // Provider responses and exception messages can contain the rotated token.
            log.warn("GitLab token inspection or rotation failed: workspaceId={}", workspaceId);
        }
    }

    @TransactionalEventListener
    public void onReplaced(ConnectionCredentialsReplacedEvent event) {
        if (event.kind() == IntegrationKind.GITLAB) {
            var service = tokenServices.getIfAvailable();
            if (service != null) service.invalidateCache(event.workspaceId());
        }
    }
}
