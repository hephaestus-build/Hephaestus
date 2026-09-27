package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Connection lookups + state transitions. Transitions are guarded by
 * {@link IntegrationState#canTransitionTo}; same-state calls are no-ops. The audit
 * row's correlation_id uniqueness silences webhook redelivery.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectionAuditRepository auditRepository;
    private final CredentialBundleConverter credentialConverter;
    private final ApplicationEventPublisher eventPublisher;
    private final SyncJobService syncJobService;

    private final TransactionTemplate providerTeardownTemplate;
    private final CredentialReader credentialReader;

    public ConnectionService(
            ConnectionRepository connectionRepository,
            ConnectionAuditRepository auditRepository,
            CredentialBundleConverter credentialConverter,
            ApplicationEventPublisher eventPublisher,
            SyncJobService syncJobService,
            PlatformTransactionManager transactionManager,
            CredentialReader credentialReader) {
        this.connectionRepository = connectionRepository;
        this.credentialReader = credentialReader;
        this.auditRepository = auditRepository;
        this.credentialConverter = credentialConverter;
        this.eventPublisher = eventPublisher;
        this.syncJobService = syncJobService;
        this.providerTeardownTemplate = new TransactionTemplate(transactionManager);
        this.providerTeardownTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Transactional(readOnly = true)
    public Optional<Connection> findActive(long workspaceId, IntegrationKind kind) {
        return connectionRepository.findActive(workspaceId, kind);
    }

    /**
     * Ids of every workspace with an ACTIVE Connection of the given kind — the fan-out a periodic sync
     * scheduler iterates so a freshly connected workspace is picked up even before it has mirrored any data.
     */
    @Transactional(readOnly = true)
    public List<Long> findWorkspaceIdsWithActiveConnection(IntegrationKind kind) {
        return connectionRepository.findWorkspaceIdsByKindAndState(kind, IntegrationState.ACTIVE);
    }

    /**
     * Kind of the workspace's SCM Connection. The invariant is "one workspace, one
     * SCM provider" — if both ACTIVE rows exist that's corrupt data and we fail loud.
     */
    @Transactional(readOnly = true)
    public Optional<IntegrationKind> findActiveProviderKind(long workspaceId) {
        boolean github = connectionRepository
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .isPresent();
        boolean gitlab = connectionRepository
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .isPresent();
        if (github && gitlab) {
            throw new IllegalStateException("Workspace " + workspaceId
                    + " has ACTIVE Connections for both GITHUB and GITLAB; "
                    + "out-of-band fix required");
        }
        if (github) return Optional.of(IntegrationKind.GITHUB);
        if (gitlab) return Optional.of(IntegrationKind.GITLAB);
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public Optional<ConnectionConfig.GitHubAppConfig> findActiveGitHubAppConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.GitHubAppConfig)
                .map(c -> (ConnectionConfig.GitHubAppConfig) c);
    }

    @Transactional(readOnly = true)
    public Optional<ConnectionConfig.GitHubPatConfig> findActiveGitHubPatConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.GITHUB)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.GitHubPatConfig)
                .map(c -> (ConnectionConfig.GitHubPatConfig) c);
    }

    @Transactional(readOnly = true)
    public Optional<ConnectionConfig.GitLabConfig> findActiveGitLabConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.GitLabConfig)
                .map(c -> (ConnectionConfig.GitLabConfig) c);
    }

    @Transactional(readOnly = true)
    public Optional<ConnectionConfig.SlackConfig> findSlackNotificationConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.SlackConfig)
                .map(c -> (ConnectionConfig.SlackConfig) c);
    }

    @Transactional(readOnly = true)
    public Optional<ConnectionConfig.OutlineConfig> findActiveOutlineConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.OUTLINE)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.OutlineConfig)
                .map(c -> (ConnectionConfig.OutlineConfig) c);
    }

    /**
     * Resolves the ACTIVE Outline Connection that registered the given change-notification
     * subscription id, returning its workspace and stored signing secret. The subscription id
     * arrives in an inbound webhook body as an <em>untrusted routing key</em> — it only selects
     * which stored secret to verify against, so a forged id simply matches nothing. Empty when no
     * ACTIVE Outline connection carries that subscription (or it has no stored secret).
     *
     * <p>Reached from {@code OutlineWebhookSecretSource#getSecret} BEFORE the HMAC comparison, on a
     * route the auth rate limiter exempts — so it must be a single indexed probe, never a scan over
     * the connected fleet. {@link ConnectionRepository#findOutlineSubscriptionsBySubscriptionId}
     * supplies exactly that.
     *
     * <p>Fail-closed on ambiguity: a subscription id is globally unique at Outline, so more than one
     * ACTIVE match means corrupt data. We reject the delivery rather than pick a row — guessing would
     * let a hostile/duplicated row decide which workspace's secret verifies a delivery.
     */
    @Transactional(readOnly = true)
    public Optional<OutlineSubscription> findOutlineSubscription(@Nullable String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            return Optional.empty();
        }
        List<ConnectionRepository.OutlineSubscriptionProjection> matches =
                connectionRepository.findOutlineSubscriptionsBySubscriptionId(subscriptionId);
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            // Never log the (attacker-supplied) subscription id itself.
            log.error(
                    "Outline webhook subscription id matches {} ACTIVE connections — rejecting delivery; out-of-band fix required",
                    matches.size());
            return Optional.empty();
        }
        ConnectionRepository.OutlineSubscriptionProjection match = matches.getFirst();
        String secret = match.getSigningSecret();
        String serverUrl = match.getServerUrl();
        if (secret == null
                || secret.isBlank()
                || serverUrl == null
                || serverUrl.isBlank()
                || match.getWorkspaceId() == null) {
            return Optional.empty();
        }
        return Optional.of(new OutlineSubscription(match.getWorkspaceId(), serverUrl, secret));
    }

    public record OutlineSubscription(long workspaceId, String serverUrl, String signingSecret) {}

    /**
     * Decrypts the stored {@link BearerToken} for the workspace's ACTIVE Connection,
     * if any. A credential the configured keys cannot read surfaces as {@link CredentialUnreadableException}.
     */
    @Transactional(readOnly = true)
    public Optional<BearerToken> findActiveBearerToken(long workspaceId, IntegrationKind kind) {
        return connectionRepository
                .findActive(workspaceId, kind)
                .flatMap(credentialReader::credentialsOf)
                .flatMap(b -> b instanceof BearerToken bt ? Optional.of(bt) : Optional.empty());
    }

    /**
     * The Connection by id within its workspace, regardless of state. Deactivation-time cleanup
     * (e.g. tearing down a vendor webhook) runs after the row left ACTIVE, so {@link #findActive}
     * can no longer resolve it.
     */
    @Transactional(readOnly = true)
    public Optional<Connection> findInWorkspace(long workspaceId, long connectionId) {
        return connectionRepository.findByIdAndWorkspaceId(connectionId, workspaceId);
    }

    /** Resolves by connection id, then instance key, then active connection. */
    @Transactional(readOnly = true)
    public Optional<Connection> findReferenced(IntegrationRef ref) {
        if (ref.connectionId() != null) {
            return connectionRepository
                    .findByIdAndWorkspaceId(ref.connectionId(), ref.workspaceId())
                    .filter(c -> c.getKind() == ref.kind());
        }
        if (ref.instanceKey() != null) {
            return connectionRepository.findByWorkspaceIdAndKindAndInstanceKey(
                    ref.workspaceId(), ref.kind(), ref.instanceKey());
        }
        return connectionRepository.findActive(ref.workspaceId(), ref.kind());
    }

    @Transactional(readOnly = true)
    public boolean hasOtherInstalledConnection(IntegrationRef ref) {
        if (ref.connectionId() == null || ref.instanceKey() == null) {
            return false;
        }
        return connectionRepository.existsByKindAndInstanceKeyAndStateNotAndIdNot(
                ref.kind(), ref.instanceKey(), IntegrationState.UNINSTALLED, ref.connectionId());
    }

    /**
     * Decrypts the stored {@link BearerToken} of one Connection regardless of state. Empty once
     * credentials were purged (the UNINSTALLED transition) — SUSPENDED rows still resolve.
     */
    @Transactional(readOnly = true)
    public Optional<BearerToken> findBearerToken(long workspaceId, long connectionId) {
        return connectionRepository
                .findByIdAndWorkspaceId(connectionId, workspaceId)
                .flatMap(credentialReader::credentialsOf)
                .flatMap(b -> b instanceof BearerToken bt ? Optional.of(bt) : Optional.empty());
    }

    /**
     * Mutate the {@link ConnectionConfig} on the workspace's ACTIVE Connection.
     * Empty when no ACTIVE row exists — callers MUST NOT auto-create. The mutator
     * must return the same sealed-subtype variant; cross-variant returns throw.
     */
    @Transactional
    public Optional<Connection> updateConfig(
            long workspaceId, IntegrationKind kind, UnaryOperator<ConnectionConfig> mutator) {
        return connectionRepository.findActive(workspaceId, kind).map(c -> {
            ConnectionConfig next = mutator.apply(c.getConfig());
            if (next == null) {
                throw new IllegalArgumentException(
                        "Mutator returned null for connection " + c.getId() + " — config must be non-null");
            }
            if (!next.getClass().equals(c.getConfig().getClass())) {
                throw new IllegalArgumentException("Mutator changed config variant on connection " + c.getId()
                        + ": "
                        + c.getConfig().getClass().getSimpleName()
                        + " → "
                        + next.getClass().getSimpleName());
            }
            c.setConfig(next);
            return connectionRepository.save(c);
        });
    }

    /**
     * Replace the credential blob on the workspace's ACTIVE Connection. Empty when no ACTIVE row
     * exists. A GitHub App connection runs on its installation, not on a stored token, so it refuses
     * one here, inside the write, rather than in a caller's earlier look at a row that may since have
     * changed mode.
     */
    @Transactional
    public Optional<BearerTokenReplacement> rotateBearerToken(
            long workspaceId, IntegrationKind kind, BearerToken bundle) {
        return connectionRepository.findActive(workspaceId, kind).map(c -> {
            if (c.getConfig() instanceof ConnectionConfig.GitHubAppConfig) {
                throw new ConnectionModeConflictException("The GitHub connection of workspace " + workspaceId
                        + " is an App installation, which runs on no stored token; there is nothing to replace.");
            }
            boolean replacedExisting = c.hasCredentials();
            c.setCredentials(bundle, credentialConverter);
            return new BearerTokenReplacement(connectionRepository.save(c), replacedExisting);
        });
    }

    /**
     * Upsert the workspace's GitHub App binding to {@code installationId}. Retires
     * any non-matching SCM-side row first (clearing its credentials via the
     * {@link IntegrationState#UNINSTALLED} transition), then activates the
     * {@code (workspace, GITHUB, installationId)} row. Idempotent on
     * {@code correlationId}.
     */
    @Transactional
    public Connection upsertGitHubAppConnection(
            Workspace workspace, long installationId, @Nullable String accountLogin, String correlationId) {
        String instanceKey = Long.toString(installationId);

        // Invariant: one ACTIVE GitHub Connection per workspace.
        for (Connection existing :
                connectionRepository.findByWorkspaceIdAndState(workspace.getId(), IntegrationState.ACTIVE)) {
            if (existing.getKind() != IntegrationKind.GITHUB) {
                continue;
            }
            if (instanceKey.equals(existing.getInstanceKey())) {
                continue;
            }
            applyTransition(
                    existing,
                    new TransitionRequest(
                            IntegrationState.UNINSTALLED,
                            "REPLACED_BY_INSTALL",
                            "SYSTEM",
                            "github-install-" + installationId,
                            correlationId + "-retire-" + existing.getId(),
                            "Replaced by GitHub App installation " + installationId),
                    null);
        }

        Connection connection = connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspace.getId(), IntegrationKind.GITHUB, instanceKey)
                .orElseGet(() -> {
                    ConnectionConfig.GitHubAppConfig config = new ConnectionConfig.GitHubAppConfig(
                            installationId, accountLogin, /* serverUrl */ null, Set.of());
                    Connection fresh = new Connection(workspace, IntegrationKind.GITHUB, instanceKey, config);
                    fresh.setDisplayName(accountLogin);
                    return connectionRepository.save(fresh);
                });

        // Refresh accountLogin/orgLogin if renamed since first bind.
        if (accountLogin != null
                && !accountLogin.isBlank()
                && connection.getConfig() instanceof ConnectionConfig.GitHubAppConfig current
                && !accountLogin.equals(current.orgLogin())) {
            connection.setConfig(new ConnectionConfig.GitHubAppConfig(
                    installationId, accountLogin, current.serverUrl(), current.enabledStreams()));
            connection.setDisplayName(accountLogin);
            connection = connectionRepository.save(connection);
        }

        if (connection.getState() != IntegrationState.ACTIVE) {
            connection = applyTransition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.ACTIVE,
                            "INSTALL_BIND",
                            "GITHUB_WEBHOOK",
                            "github-install-" + installationId,
                            correlationId,
                            "Linked workspace to GitHub App installation " + installationId),
                    null);
        }

        return connection;
    }

    /**
     * Upsert + activate the workspace's PAT Connection row. Idempotent on
     * {@code (workspace, kind, instanceKey)}.
     */
    @Transactional
    public void provisionPatConnection(
            Workspace workspace,
            IntegrationKind kind,
            @Nullable String instanceKey,
            ConnectionConfig config,
            String token,
            String correlationId) {
        Connection connection = connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspace.getId(), kind, instanceKey)
                .orElseGet(() -> {
                    Connection fresh = new Connection(workspace, kind, instanceKey, config);
                    fresh.setDisplayName(workspace.getAccountLogin());
                    return connectionRepository.save(fresh);
                });

        if (connection.getState() != IntegrationState.ACTIVE) {
            connection = applyTransition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.ACTIVE,
                            "PAT_PROVISIONED",
                            "SYSTEM",
                            "scm-connection-provisioner",
                            correlationId,
                            "Provisioned PAT connection on workspace creation"),
                    null);
        }

        if (token != null && !token.isBlank()) {
            connection.setCredentials(new BearerToken(token, null), credentialConverter);
            connectionRepository.save(connection);
        }
    }

    /**
     * Transition the Connection to {@code req.next()}. Idempotent on same-state and on
     * duplicate {@code correlationId}; invalid transitions throw. Transitioning to
     * {@link IntegrationState#UNINSTALLED} clears credentials atomically.
     *
     * <p>Authoritative: a sync job in flight does NOT block this. Every caller here is the system or
     * the vendor stating a fact — workspace PURGE erasing credentials, Slack telling us the app was
     * uninstalled, a GitHub install replacing its predecessor. None of them can be retried by a human
     * and none may be preempted by a background reconcile, so they win over the job and let it fail on
     * its own. Only the interactive admin door ({@link #disconnect}) fences, because only it has
     * someone to hand a 409 to.
     */
    @Transactional
    public Connection transition(Connection connection, TransitionRequest req) {
        return applyTransition(connection, req, null);
    }

    /**
     * Admin-initiated disconnect: the one door that refuses to uninstall out from under a running sync.
     *
     * <p>The fence is here rather than in {@link #applyTransition} because it trades availability for
     * tidiness — acceptable when an admin clicked a button and can be told "retry", never acceptable
     * for erasure or a vendor-driven uninstall.
     *
     * <p>It cannot wedge. Under the connection's lifecycle lock we first reap leases the hourly sweep
     * hasn't reached (so a job stranded by a pod crash frees the connection in minutes, not an hour —
     * the same inline reap "Sync now" already does), then request the surviving job's cancellation and
     * report it as a 409 naming that job id. The cancel request is durable even though we throw
     * ({@code noRollbackFor}), so this is a "retry in a moment", not a dead end: the runner aborts and
     * the admin's next click succeeds.
     *
     * <p>Once the fence and the state-machine check have passed, and while the row lock is still held,
     * the strategy erases the integration's local data in this transaction. An erase failure propagates
     * and rolls everything back, leaving the connection ACTIVE with its credentials so the admin can
     * retry. Provider teardown is prepared from the stored credentials before they are cleared, but runs
     * only after the transition commits, so a rolled-back disconnect never touches the provider. It is
     * best effort: an unreachable provider must not keep an erased workspace connected.
     *
     * @throws ConnectionBusyException 409 — a sync job still holds the connection; its cancellation has
     *                                 been requested, so retrying shortly will succeed
     */
    @Transactional(noRollbackFor = ConnectionBusyException.class)
    public Connection disconnect(Connection connection, TransitionRequest req, ConnectionStrategy strategy) {
        if (req.next() != IntegrationState.UNINSTALLED) {
            throw new IllegalArgumentException("Disconnect must transition to UNINSTALLED");
        }
        return applyTransition(
                connection,
                req,
                locked -> eraseAndTearDownProviderAfterCommit(locked, strategy),
                /* fenceOnActiveSyncJob */ true);
    }

    /**
     * Disconnects for mandatory erasure without allowing a sync job to delay credential removal. {@code
     * revoke} runs with this transaction suspended, so its provider calls hold no transaction open; its
     * failure still propagates and rolls the transition back.
     */
    @Transactional
    public Connection disconnectForErasure(Connection connection, TransitionRequest req, Runnable revoke) {
        if (req.next() != IntegrationState.UNINSTALLED) {
            throw new IllegalArgumentException("Disconnect must transition to UNINSTALLED");
        }
        return applyTransition(
                connection,
                req,
                locked -> providerTeardownTemplate.executeWithoutResult(status -> revoke.run()),
                /* fenceOnActiveSyncJob */ false);
    }

    private Connection applyTransition(
            Connection connection, TransitionRequest req, @Nullable Consumer<Connection> beforeLocalTransition) {
        return applyTransition(connection, req, beforeLocalTransition, /* fenceOnActiveSyncJob */ false);
    }

    private Connection applyTransition(
            Connection requested,
            TransitionRequest req,
            @Nullable Consumer<Connection> beforeLocalTransition,
            boolean fenceOnActiveSyncJob) {
        // For a persisted argument the row transitioned below is the one re-read under the lifecycle
        // lock, never the caller's possibly-stale instance.
        Connection connection = requested;
        if (requested.getId() != null) {
            long connectionId = requested.getId();
            long workspaceId = requested.getWorkspace().getId();
            connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
            connection = connectionRepository
                    .findByIdAndWorkspaceId(connectionId, workspaceId)
                    .orElseThrow(() -> new EntityNotFoundException("Connection", connectionId));
            if (fenceOnActiveSyncJob) {
                // Inside the lock, so no job can slip in between this check and the state write.
                syncJobService.requestCancelForTeardown(connectionId).ifPresent(activeJobId -> {
                    throw new ConnectionBusyException(connectionId, activeJobId);
                });
            }
        }
        IntegrationState current = connection.getState();
        if (current == req.next()) {
            log.debug("Connection {} already in state {}, no-op", connection.getId(), req.next());
            return connection;
        }
        if (!current.canTransitionTo(req.next()) && !isGuardedReconnect(connection, current, req)) {
            throw new IllegalStateException(
                    "Illegal transition for connection " + connection.getId() + ": " + current + " → " + req.next());
        }
        if (beforeLocalTransition != null) {
            beforeLocalTransition.accept(connection);
        }
        ConnectionAudit audit = new ConnectionAudit(
                connection,
                req.eventType(),
                current,
                req.next(),
                req.actorKind(),
                req.actorRef(),
                req.correlationId(),
                req.detail());
        try {
            auditRepository.save(audit);
        } catch (DataIntegrityViolationException e) {
            log.info(
                    "Idempotent {} for connection={} corr={} (already recorded)",
                    req.eventType(),
                    connection.getId(),
                    req.correlationId());
            return connection;
        }
        connection.setState(req.next());
        connection.setStateReason(req.detail());
        if (req.next() == IntegrationState.UNINSTALLED) {
            if (connection.getCredentialsEncrypted() != null) {
                connection.setCredentials(null, credentialConverter);
                log.info("Purged credentials on UNINSTALLED transition for connection={}", connection.getId());
            }
            if (connection.getConfig() instanceof ConnectionConfig.GitLabConfig gitLabConfig) {
                connection.setConfig(gitLabConfig.withGitlabWebhookId(null));
            } else if (connection.getConfig() instanceof ConnectionConfig.OutlineConfig outlineConfig) {
                connection.setConfig(outlineConfig.withWebhookSubscription(null, null));
            }
        }
        Connection saved = connectionRepository.save(connection);
        publishLifecycleEvent(saved, current, req.next());
        return saved;
    }

    /**
     * The teardown is prepared with this transaction suspended, so a failed credential read cannot mark
     * the disconnect rollback-only; those reads see the committed row, credentials included, since the
     * lifecycle lock blocks writers, not readers.
     */
    private void eraseAndTearDownProviderAfterCommit(Connection connection, ConnectionStrategy strategy) {
        IntegrationRef ref = new IntegrationRef(
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getInstanceKey(),
                connection.getId());
        strategy.eraseLocalData(ref);
        Optional<Runnable> teardown;
        try {
            teardown = Objects.requireNonNull(
                    providerTeardownTemplate.execute(status -> strategy.prepareProviderTeardown(ref)));
        } catch (RuntimeException e) {
            log.warn(
                    "Provider teardown could not be prepared on disconnect of connection={} kind={}: {} — disconnecting without it",
                    ref.connectionId(),
                    ref.kind(),
                    e.toString());
            return;
        }
        teardown.ifPresent(call ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            call.run();
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Provider teardown failed after disconnect of connection={} kind={}: {} — local data already erased",
                                    ref.connectionId(),
                                    ref.kind(),
                                    e.toString());
                        }
                    }
                }));
    }

    /**
     * Signal a genuine ACTIVE-boundary crossing to vendor adapters (AFTER_COMMIT listeners).
     * Fires inside the transition transaction so a rollback also drops the event; the
     * same-state and duplicate-correlation early returns above keep replays silent.
     */
    private void publishLifecycleEvent(Connection connection, IntegrationState previous, IntegrationState next) {
        long workspaceId = connection.getWorkspace().getId();
        if (next == IntegrationState.ACTIVE) {
            eventPublisher.publishEvent(
                    new ConnectionLifecycleEvent.Activated(connection.getId(), workspaceId, connection.getKind()));
        } else if (previous == IntegrationState.ACTIVE) {
            eventPublisher.publishEvent(
                    new ConnectionLifecycleEvent.Deactivated(connection.getId(), workspaceId, connection.getKind()));
        }
    }

    /**
     * Guarded revival of a terminal UNINSTALLED row — the kind-specific reconnect flows the
     * {@link IntegrationState} javadoc reserves. Two doors only: a completed Slack OAuth round-trip,
     * and an admin-driven inline re-connect ({@code INITIATE}) whose strategy just re-validated
     * fresh credentials against the vendor. Both preserve the vendor natural key
     * {@code (workspace, kind, instance_key)} instead of colliding with the unique constraint.
     */
    private static boolean isGuardedReconnect(
            Connection connection, IntegrationState current, TransitionRequest request) {
        if (current != IntegrationState.UNINSTALLED || request.next() != IntegrationState.ACTIVE) {
            return false;
        }
        if (connection.getKind() == IntegrationKind.SLACK && "OAUTH_COMPLETE".equals(request.eventType())) {
            return true;
        }
        return "INITIATE".equals(request.eventType());
    }

    /** Parameter object for {@link #transition}. */
    public record TransitionRequest(
            IntegrationState next,
            String eventType,
            String actorKind,
            @Nullable String actorRef,
            @Nullable String correlationId,
            @Nullable String detail) {}
}
