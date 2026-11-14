package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import de.tum.cit.aet.hephaestus.core.lock.ClusterLock;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabTokenMetadata;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.CredentialFence;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.Credentials;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.RotatedToken;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.TokenInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Token HTTP calls run without a database transaction; a cluster lock serializes scheduler and initialization checks. */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
@Slf4j
public class GitLabTokenLifecycleService {
    private final ConnectionService connections;
    private final ConnectionCommands commands;
    private final GitLabTokenRotationClient rotationClient;
    private final GitLabTokenService tokenService;
    private final GitLabCredentialHealth health;
    private final WebhookProperties properties;
    private final Clock clock;
    private final ClusterLock locks;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public GitLabTokenLifecycleService(
            ConnectionService connections,
            ConnectionCommands commands,
            GitLabTokenRotationClient rotationClient,
            GitLabTokenService tokenService,
            GitLabCredentialHealth health,
            WebhookProperties properties,
            Clock clock,
            ClusterLock locks,
            PlatformTransactionManager transactions) {
        this.connections = connections;
        this.commands = commands;
        this.rotationClient = rotationClient;
        this.tokenService = tokenService;
        this.health = health;
        this.properties = properties;
        this.clock = clock;
        this.locks = locks;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.reads.setTimeout(30);
        this.writes = new TransactionTemplate(transactions);
        this.writes.setTimeout(30);
    }

    public void check(long workspaceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("GitLab token inspection requires no active database transaction");
        // A session lock has no expiry, unlike a lease: a holder that stalls before its rotation request keeps it. A
        // successor that rotated first would otherwise let the stalled request reuse the revoked token, and GitLab
        // answers that reuse by revoking the whole token family. A process that dies drops the lock.
        locks.run("gitlab-token-" + workspaceId, Duration.ZERO, () -> {
            var snapshot = reads.execute(status -> snapshot(workspaceId));
            if (snapshot != null) inspect(snapshot);
        });
    }

    private @Nullable Check snapshot(long workspaceId) {
        var candidate = connections.findActive(workspaceId, IntegrationKind.GITLAB);
        if (candidate.isEmpty() || !(candidate.get().getConfig() instanceof GitLabConfig config)) return null;
        var connection = candidate.get();
        var token = connections.findBearerToken(workspaceId, connection.getId());
        if (token.isEmpty()) return null;
        return new Check(
                new Credentials(
                        workspaceId,
                        tokenService.serverUrlOf(config.serverUrl()),
                        token.get().token()),
                new CredentialFence(
                        connection.getId(), connection.getCredentialsGeneration(), connection.getLifecycleGeneration()),
                config);
    }

    private void inspect(Check check) {
        long workspaceId = check.credentials().workspaceId();
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        int threshold = properties.tokenRotation().thresholdDays();
        boolean expiring = check.config().tokenMetadata() != null
                && isExpiring(check.config().tokenMetadata().expiresAt(), today, threshold);
        boolean attempted = false;
        TokenInfo inspected = null;
        RotatedToken completed = null;
        try {
            var info = rotationClient.getTokenInfo(check.credentials());
            inspected = info;
            expiring = isExpiring(info.expiresAt(), today, threshold);
            RotatedToken rotated = null;
            if (expiring && threshold > 0) {
                // No retry: a lost response may have revoked the old token. Re-rotation revokes its whole family.
                if (!Boolean.TRUE.equals(
                        reads.execute(status -> current(check, false).isPresent()))) return;
                attempted = true;
                rotated = rotationClient.rotateToken(
                        check.credentials(),
                        today.plusDays(properties.tokenRotation().validityDays()));
            }
            completed = rotated;
            var result = rotated;
            boolean expiry = expiring;
            writes.executeWithoutResult(status -> finish(check, info, result, expiry));
        } catch (RuntimeException failure) {
            boolean rotationAttempted = attempted;
            boolean expiry = expiring;
            var info = inspected;
            var result = completed;
            Signal signal = failure instanceof WebClientResponseException response
                            && response.getStatusCode().value() == 401
                    ? Signal.REFUSED
                    : rotationAttempted || expiry ? Signal.EXPIRING : Signal.NONE;
            // Provider responses and exception messages can contain a token. Log before the recovery write, which can
            // itself fail, so an attempted rotation is always visible.
            log.warn(
                    "GitLab token inspection or rotation failed: workspaceId={}, rotationAttempted={}",
                    workspaceId,
                    attempted);
            writes.executeWithoutResult(status -> recordFailure(check, info, result, rotationAttempted, signal));
        }
    }

    private void finish(Check check, TokenInfo info, @Nullable RotatedToken rotated, boolean expiring) {
        long workspaceId = check.credentials().workspaceId();
        Optional<Connection> admitted;
        if (rotated != null) {
            // The fence rejects a result after the lease. The credential owner coordinates provider identities before
            // taking its lifecycle lock.
            admitted = connections
                    .rotateBearerToken(
                            workspaceId,
                            IntegrationKind.GITLAB,
                            new BearerToken(
                                    rotated.token(),
                                    rotated.expiresAt().atStartOfDay().toInstant(ZoneOffset.UTC)),
                            check.fence())
                    .map(replacement -> replacement.connection());
        } else admitted = current(check, true);
        if (admitted.isEmpty()) {
            if (rotated != null) {
                recordFailure(check, info, rotated, true, Signal.EXPIRING);
                log.warn("Discarded a stale GitLab rotation result: workspaceId={}", workspaceId);
            }
            return;
        }
        var connection = admitted.get();
        var config = (GitLabConfig) connection.getConfig();
        connection.setConfig(config.withTokenMetadata(
                new GitLabTokenMetadata(rotated == null ? info.expiresAt() : rotated.expiresAt(), clock.instant())));
        if (rotated == null) health.observe(workspaceId, check.credentials().token(), false);
        health.expiring(workspaceId, rotated == null && expiring);
    }

    private void recordFailure(
            Check check, @Nullable TokenInfo info, @Nullable RotatedToken completed, boolean attempted, Signal signal) {
        held(check).ifPresent(connection -> {
            long workspaceId = check.credentials().workspaceId();
            // An after-commit listener can fail after the returned credential is already durable.
            if (completed != null
                    && connections
                            .findBearerToken(workspaceId, connection.getId())
                            .map(token -> token.token().equals(completed.token()))
                            .orElse(false)) return;
            if (attempted)
                commands.recordSystemEvent(
                        connection,
                        "GITLAB_TOKEN_ROTATION_UNCERTAIN",
                        "Rotation was attempted but its result could not be stored. Verify the token before rotating it again.");
            // Audit a discarded result on its original row, but never mark a replacement credential as broken.
            if (connection.getState() != IntegrationState.ACTIVE
                    || connection.getCredentialsGeneration() != check.fence().credentialsGeneration()) return;
            if (info != null
                    && connection.getLifecycleGeneration() == check.fence().lifecycleGeneration()
                    && connection.getConfig() instanceof GitLabConfig config)
                connection.setConfig(
                        config.withTokenMetadata(new GitLabTokenMetadata(info.expiresAt(), clock.instant())));
            switch (signal) {
                case REFUSED -> health.observe(workspaceId, check.credentials().token(), true);
                case EXPIRING -> health.expiring(workspaceId, true);
                case NONE -> {}
            }
        });
    }

    private Optional<Connection> held(Check check) {
        long workspaceId = check.credentials().workspaceId();
        commands.holdLifecycleLock(workspaceId, check.fence().connectionId());
        return readConnection(check);
    }

    private Optional<Connection> readConnection(Check check) {
        return connections
                .findInWorkspace(
                        check.credentials().workspaceId(), check.fence().connectionId())
                .map(connection -> {
                    Objects.requireNonNull(entityManager).refresh(connection);
                    return connection;
                });
    }

    private Optional<Connection> current(Check check, boolean lock) {
        return (lock ? held(check) : readConnection(check))
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE
                        && check.fence().matches(connection));
    }

    private static boolean isExpiring(@Nullable LocalDate expiry, LocalDate today, int threshold) {
        return expiry != null && !expiry.isAfter(today.plusDays(Math.max(0, threshold)));
    }

    private record Check(Credentials credentials, CredentialFence fence, GitLabConfig config) {}

    /** The credential health a failed check reports for the token it inspected. */
    private enum Signal {
        REFUSED,
        EXPIRING,
        NONE
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @TransactionalEventListener
    public void onReplaced(ConnectionCredentialsReplacedEvent event) {
        if (event.kind() == IntegrationKind.GITLAB) tokenService.invalidateCache(event.workspaceId());
    }
}
