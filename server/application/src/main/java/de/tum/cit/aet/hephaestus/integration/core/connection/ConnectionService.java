package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncStateChangedEvent;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connection lookups and state transitions. {@link Connection#transitionTo} owns the state guard.
 * The application service holds the scoped lifecycle lock and checks the audit identity before local erasure;
 * same-state calls and recorded deliveries are no-ops.
 */
@Service
public class ConnectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionService.class);

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ConnectionRepository connectionRepository;
    private final ConnectionAuditRepository auditRepository;
    private final CredentialBundleConverter credentialConverter;
    private final ApplicationEventPublisher eventPublisher;
    private final SyncJobService syncJobService;

    private final ConnectionLifecycleEffects lifecycleEffects;
    private final CredentialReader credentialReader;
    private final SourceProviderNamespaces sourceNamespaces;
    private final Clock clock;
    private final ProviderResourceRepairs resourceRepairs;

    public ConnectionService(
            ConnectionRepository connectionRepository,
            ConnectionAuditRepository auditRepository,
            CredentialBundleConverter credentialConverter,
            ApplicationEventPublisher eventPublisher,
            SyncJobService syncJobService,
            CredentialReader credentialReader,
            SourceProviderNamespaces sourceNamespaces,
            Clock clock,
            ProviderResourceRepairs resourceRepairs) {
        this.connectionRepository = connectionRepository;
        this.clock = clock;
        this.resourceRepairs = resourceRepairs;
        this.sourceNamespaces = sourceNamespaces;
        this.credentialReader = credentialReader;
        this.auditRepository = auditRepository;
        this.credentialConverter = credentialConverter;
        this.eventPublisher = eventPublisher;
        this.syncJobService = syncJobService;
        this.lifecycleEffects = new ConnectionLifecycleEffects(eventPublisher);
    }

    /**
     * Checks the destination before reading credentials, from the same connection snapshot. A failed
     * credential read propagates without marking a caller's transaction rollback-only.
     */
    @Transactional(
            readOnly = true,
            noRollbackFor = {CredentialUnreadableException.class, MissingCredentialKeyException.class})
    public Optional<BearerConnection> findActiveBearerConnection(
            long workspaceId, IntegrationKind kind, Function<ConnectionConfig, @Nullable String> serverForConfig) {
        return connectionRepository.findActive(workspaceId, kind).flatMap(connection -> {
            String serverUrl = serverForConfig.apply(connection.getConfig());
            if (serverUrl == null) return Optional.empty();
            return credentialReader
                    .credentialsOf(connection)
                    .filter(BearerToken.class::isInstance)
                    .map(BearerToken.class::cast)
                    .map(bearer -> new BearerConnection(serverUrl, bearer));
        });
    }

    public record BearerConnection(String serverUrl, BearerToken bearer) {}

    @Transactional(readOnly = true)
    public Optional<Connection> findActive(long workspaceId, IntegrationKind kind) {
        return connectionRepository.findActive(workspaceId, kind);
    }

    @Transactional(readOnly = true)
    public List<Connection> findActiveConnections(long workspaceId, IntegrationKind kind) {
        return connectionRepository.findByWorkspaceIdAndState(workspaceId, IntegrationState.ACTIVE).stream()
                .filter(connection -> connection.getKind() == kind)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<Long> findUniqueActiveWorkspaceId(IntegrationKind kind, String instanceKey) {
        if (instanceKey.isBlank()) return Optional.empty();
        var matches = connectionRepository.findWorkspaceIdsByKindAndInstanceKeyAndState(
                kind, instanceKey, IntegrationState.ACTIVE, Limit.of(2));
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * Ids of every workspace with a Connection of the given kind in one of the states — the fan-out a periodic sync
     * scheduler iterates so a freshly connected workspace is picked up even before it has mirrored any data.
     */
    @Transactional(readOnly = true)
    public List<Long> findWorkspaceIdsWithConnection(IntegrationKind kind, IntegrationState... states) {
        List<Long> ids = new ArrayList<>();
        for (IntegrationState state : states) {
            ids.addAll(connectionRepository.findWorkspaceIdsByKindAndState(kind, state));
        }
        return ids;
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
            throw new IllegalStateException(
                    "This workspace has active GitHub and GitLab connections, which Hephaestus does not support. Ask your instance operator to fix it.");
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
    public Optional<ConnectionConfig.SlackConfig> findSlackConfig(long workspaceId) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(Connection::getConfig)
                .filter(c -> c instanceof ConnectionConfig.SlackConfig)
                .map(c -> (ConnectionConfig.SlackConfig) c);
    }

    /**
     * Resolves the ACTIVE Outline Connection that registered the given change-notification
     * subscription id, returning its workspace and the route the subscription posts to. The
     * subscription id comes from a delivery body; the consumer accepts the delivery only when the
     * route it was received on is the one returned here. Empty when no ACTIVE Outline connection
     * carries that subscription (or it has no stored route).
     *
     * <p>Reached once per consumed delivery, so it is a single indexed probe, never a scan over the
     * connected fleet. {@link ConnectionRepository#findOutlineSubscriptionsBySubscriptionId}
     * supplies exactly that.
     *
     * <p>Fail-closed on ambiguity: a subscription id is globally unique at Outline, so more than one
     * ACTIVE match means corrupt data. We reject the delivery rather than pick a row — guessing would
     * let a hostile/duplicated row decide which workspace a delivery refreshes.
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
        String route = match.getRoute();
        String serverUrl = match.getServerUrl();
        if (route == null
                || route.isBlank()
                || serverUrl == null
                || serverUrl.isBlank()
                || match.getWorkspaceId() == null) {
            return Optional.empty();
        }
        return Optional.of(new OutlineSubscription(match.getWorkspaceId(), serverUrl, route));
    }

    public record OutlineSubscription(long workspaceId, String serverUrl, String route) {}

    /**
     * Decrypts the stored {@link BearerToken} for the workspace's ACTIVE Connection,
     * if any. A credential the configured keys cannot read surfaces as {@link CredentialUnreadableException}.
     * That exception leaves a joined transaction committable, so a batch over every workspace can skip
     * the unreadable one and still return the others.
     */
    @Transactional(readOnly = true, noRollbackFor = CredentialUnreadableException.class)
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
     * Mutates the active connection's config. The mutator must be pure: it runs again after the lifecycle lock
     * refreshes the current config, so a concurrent project stop or other setting is retained.
     */
    @Transactional
    public Optional<Connection> updateConfig(
            long workspaceId, IntegrationKind kind, UnaryOperator<ConnectionConfig> mutator) {
        return connectionRepository.findActive(workspaceId, kind).flatMap(connection -> {
            ConnectionConfig planned = connection.getConfig().updatedBy(mutator);
            Class<?> mode = connection.getConfig().getClass();
            var credential = resourceRepairs.retainedCredential(connection);
            var identities = new ArrayList<>(resourceRepairs.resources(
                    ProviderResourceRepairs.ref(connection), connection.getConfig(), credential));
            identities.addAll(resourceRepairs.resources(ProviderResourceRepairs.ref(connection), planned, credential));
            resourceRepairs.requireAdoptableChange(connection, planned, credential);
            connectionRepository.acquireLifecycleLock(connection.getId(), workspaceId);
            Objects.requireNonNull(entityManager).refresh(connection);
            if (connection.getState() != IntegrationState.ACTIVE) return Optional.empty();
            if (!mode.equals(connection.getConfig().getClass()))
                throw new TransientDataAccessResourceException("The connection mode changed during its config update");
            ConnectionConfig next = connection.getConfig().updatedBy(mutator);
            var currentCredential = resourceRepairs.retainedCredential(connection);
            var current = new ArrayList<>(resourceRepairs.resources(
                    ProviderResourceRepairs.ref(connection), connection.getConfig(), currentCredential));
            current.addAll(resourceRepairs.resources(ProviderResourceRepairs.ref(connection), next, currentCredential));
            resourceRepairs.requireCoordinated(identities, current);
            resourceRepairs.requireAdoptable(ProviderResourceRepairs.ref(connection), next, currentCredential);
            boolean namespaceChanged = !Objects.equals(
                    SourceProviderNamespaces.configuredUrl(connection.getConfig()),
                    SourceProviderNamespaces.configuredUrl(next));
            connection.setConfig(next);
            if (namespaceChanged) sourceNamespaces.ensure(kind, next);
            return Optional.of(connectionRepository.save(connection));
        });
    }

    /** Explicit project stops also survive a paused connection and its later resumption. */
    @Transactional
    public Optional<Connection> updateGitLabProjectExclusions(
            long workspaceId, UnaryOperator<ConnectionConfig.GitLabProjectExclusions> mutator) {
        return connectionRepository
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .or(() -> connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        workspaceId, IntegrationKind.GITLAB, IntegrationState.SUSPENDED))
                .filter(connection -> {
                    connectionRepository.acquireLifecycleLock(connection.getId(), workspaceId);
                    Objects.requireNonNull(entityManager).refresh(connection);
                    return connection.getState() == IntegrationState.ACTIVE
                            || connection.getState() == IntegrationState.SUSPENDED;
                })
                .filter(connection -> connection.getConfig() instanceof ConnectionConfig.GitLabConfig)
                .map(connection -> {
                    var config = (ConnectionConfig.GitLabConfig) connection.getConfig();
                    connection.setConfig(config.withProjectExclusions(mutator.apply(config.projectExclusions())));
                    Connection saved = connectionRepository.save(connection);
                    eventPublisher.publishEvent(new SyncStateChangedEvent(
                            workspaceId, saved.getId(), IntegrationKind.GITLAB, SyncStateChangedEvent.Scope.RESOURCES));
                    return saved;
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
        return connectionRepository.findActive(workspaceId, kind).flatMap(c -> {
            if (c.getConfig() instanceof ConnectionConfig.GitHubAppConfig) {
                throw new ConnectionModeConflictException("The GitHub connection of workspace "
                        + workspaceId
                        + " is an App installation. It uses no stored token, so there is nothing to replace.");
            }
            resourceRepairs.requireAdoptableChange(c, c.getConfig(), bundle);
            connectionRepository.acquireLifecycleLock(c.getId(), workspaceId);
            Objects.requireNonNull(entityManager).refresh(c);
            if (c.getState() != IntegrationState.ACTIVE || c.getConfig() instanceof ConnectionConfig.GitHubAppConfig) {
                return Optional.empty();
            }
            boolean replacedExisting = c.hasCredentials();
            c.setCredentials(bundle, credentialConverter);
            if (c.getConfig() instanceof ConnectionConfig.GitLabConfig cfg) {
                c.setConfig(cfg.withTokenMetadata(null));
            }
            eventPublisher.publishEvent(new ConnectionCredentialsReplacedEvent(c.getId(), workspaceId, kind));
            return Optional.of(new BearerTokenReplacement(connectionRepository.save(c), replacedExisting));
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
            Workspace workspace, long appId, long installationId, @Nullable String accountLogin, String correlationId) {
        String instanceKey = Long.toString(installationId);
        resourceRepairs.requireAdoptable(
                new IntegrationRef(IntegrationKind.GITHUB, workspace.getId(), instanceKey),
                new ConnectionConfig.GitHubAppConfig(installationId, appId, accountLogin, null, Set.of()),
                null);

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
                    null,
                    /* fenceOnActiveSyncJob */ false, /* retainForTeardown */
                    false);
        }

        Connection connection = connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspace.getId(), IntegrationKind.GITHUB, instanceKey)
                .orElseGet(() -> {
                    ConnectionConfig.GitHubAppConfig config =
                            new ConnectionConfig.GitHubAppConfig(installationId, appId, accountLogin, null, Set.of());
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
                    installationId, appId, accountLogin, current.serverUrl(), current.enabledStreams()));
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
                    null,
                    /* fenceOnActiveSyncJob */ false, /* retainForTeardown */
                    false);
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
                    null,
                    /* fenceOnActiveSyncJob */ false, /* retainForTeardown */
                    false);
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
        return applyTransition(connection, req, null, /* fenceOnActiveSyncJob */ false, /* retainForTeardown */ false);
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
                locked -> strategy.eraseLocalData(new IntegrationRef(
                        locked.getKind(), locked.getWorkspace().getId(), locked.getInstanceKey(), locked.getId())),
                /* fenceOnActiveSyncJob */ true,
                /* retainForTeardown */ true);
    }

    /**
     * Disconnects for mandatory erasure without allowing a sync job to delay credential removal. {@code
     * revoke} runs with this transaction suspended, so its provider calls hold no transaction open; its
     * failure still propagates and rolls the transition back. Local data is then erased under the
     * lifecycle lock before the connection loses its source authority or credentials.
     */
    @Transactional
    public Connection disconnectForErasure(Connection connection, TransitionRequest req, Runnable erase) {
        if (req.next() != IntegrationState.UNINSTALLED) {
            throw new IllegalArgumentException("Disconnect must transition to UNINSTALLED");
        }
        return applyTransition(
                connection,
                req,
                locked -> {
                    resourceRepairs.requestRemoval(locked, false);
                    erase.run();
                },
                /* fenceOnActiveSyncJob */ false, /* retainForTeardown */
                false);
    }

    /**
     * {@code retainForTeardown} keeps an UNINSTALLED connection's credential and provider ids for
     * {@link ProviderTeardown}, which removes them at the provider and then here; otherwise they go now.
     */
    private Connection applyTransition(
            Connection requested,
            TransitionRequest req,
            @Nullable Consumer<Connection> beforeLocalTransition,
            boolean fenceOnActiveSyncJob,
            boolean retainForTeardown) {
        // For a persisted argument the row transitioned below is the one re-read under the
        // lifecycle
        // lock, never the caller's possibly-stale instance.
        var credential = resourceRepairs.retainedCredential(requested);
        var identities = new ArrayList<>(
                resourceRepairs.resources(ProviderResourceRepairs.ref(requested), requested.getConfig(), credential));
        if (req.next() == IntegrationState.ACTIVE) {
            ConnectionConfig next = requested.getConfig();
            if (requested.getState() != IntegrationState.ACTIVE) {
                if (next instanceof ConnectionConfig.GitLabConfig gitlab) next = gitlab.withGitlabWebhookId(null);
                else if (next instanceof ConnectionConfig.OutlineConfig outline)
                    next = outline.withWebhookSubscription(null, null);
            }
            identities.addAll(resourceRepairs.resources(ProviderResourceRepairs.ref(requested), next, credential));
            resourceRepairs.requireAdoptableChange(requested, next, credential);
        } else {
            resourceRepairs.coordinate(identities);
        }
        Connection connection = requested;
        if (requested.getId() != null) {
            long connectionId = requested.getId();
            long workspaceId = requested.getWorkspace().getId();
            connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
            Long persistedVersion = connectionRepository
                    .findLifecycleVersion(connectionId, workspaceId)
                    .orElseThrow();
            connection = connectionRepository
                    .findByIdAndWorkspaceId(connectionId, workspaceId)
                    .orElseThrow(() -> new EntityNotFoundException("Connection", connectionId));
            if (!Objects.equals(connection.getVersion(), persistedVersion)) {
                Objects.requireNonNull(entityManager).refresh(connection);
            }
            resourceRepairs.requireCoordinated(
                    identities,
                    resourceRepairs.resources(
                            ProviderResourceRepairs.ref(connection),
                            connection.getConfig(),
                            resourceRepairs.retainedCredential(connection)));
        }
        IntegrationState current = connection.getState();
        if (current == req.next()) {
            log.debug("Connection {} already in state {}, no-op", connection.getId(), req.next());
            return connection;
        }
        if (auditRepository.containsEvent(
                connection.getId(), connection.getWorkspace().getId(), req.eventType(), req.correlationId())) {
            log.info(
                    "Skipped recorded connection event: connectionId={}, current={}, requested={}, eventType={}, correlationId={}",
                    connection.getId(),
                    current,
                    req.next(),
                    req.eventType(),
                    req.correlationId());
            return connection;
        }
        connection.requireTransitionTo(req.next(), req.eventType());
        if (fenceOnActiveSyncJob) {
            // The state guard passes before cancellation or erasure can write anything.
            long connectionId = connection.getId();
            syncJobService.requestCancelForTeardown(connectionId).ifPresent(activeJobId -> {
                throw new ConnectionBusyException(connectionId, activeJobId);
            });
        }
        if (req.next() == IntegrationState.ACTIVE) {
            if (current == IntegrationState.SUSPENDED
                    && (connection.getKind() == IntegrationKind.GITLAB
                            || connection.getKind() == IntegrationKind.OUTLINE)
                    && !connection.providerRepairWasCaptured()) {
                resourceRepairs.requestRemoval(connection, current == IntegrationState.SUSPENDED);
            }
            connection.detachProviderWebhook();
            sourceNamespaces.ensure(connection.getKind(), connection.getConfig());
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
        audit.setActorAccountId(req.actorAccountId());
        auditRepository.save(audit);
        if ((req.next() == IntegrationState.UNINSTALLED && retainForTeardown)
                || req.next() == IntegrationState.SUSPENDED) {
            resourceRepairs.requestRemoval(connection, req.next() == IntegrationState.SUSPENDED);
        }
        connection.transitionTo(req.next(), req.eventType(), req.detail());
        Instant now = clock.instant();
        if (req.next() == IntegrationState.UNINSTALLED) {
            if (retainForTeardown) {
                connection.requestProviderTeardown(now);
                connection.markProviderRepairCaptured();
            } else {
                connection.releaseProviderFootprint(credentialConverter);
            }
        }
        if (req.next() == IntegrationState.ACTIVE) connection.finishProviderCredentialRetention();
        Connection saved = connectionRepository.save(connection);
        if (req.next() == IntegrationState.UNINSTALLED && retainForTeardown) {
            eventPublisher.publishEvent(new ProviderTeardownRequested(
                    Objects.requireNonNull(saved.getId()),
                    saved.getWorkspace().getId(),
                    saved.getCredentialsGeneration()));
        }
        lifecycleEffects.publishLifecycleEvent(saved, current, req.next());
        if (req.next() == IntegrationState.UNINSTALLED && beforeLocalTransition != null)
            ConnectionErasures.record(Objects.requireNonNull(saved.getId()), saved.getWorkspace().getId());
        return saved;
    }

    /**
     * Parameter object for {@link #transition}. A correlation identifies one event for the stored connection,
     * across reconnects. Null is one identity too, as the audit index requires. A new event must use a distinct
     * non-null delivery or request ID; reconnect never makes an old delivery new again.
     */
    public record TransitionRequest(
            IntegrationState next,
            String eventType,
            String actorKind,
            @Nullable String actorRef,
            @Nullable String correlationId,
            @Nullable String detail,
            @Nullable Long actorAccountId) {
        public TransitionRequest {
            if (("ADMIN".equals(actorKind) || "USER".equals(actorKind)) && actorRef != null)
                throw new IllegalArgumentException("Personal actors require a typed account reference");
        }

        /** Provider and system events have no account actor. */
        public TransitionRequest(
                IntegrationState next,
                String eventType,
                String actorKind,
                @Nullable String actorRef,
                @Nullable String correlationId,
                @Nullable String detail) {
            this(next, eventType, actorKind, actorRef, correlationId, detail, null);
        }

        public static TransitionRequest byAccount(
                IntegrationState next,
                String eventType,
                String actorKind,
                @Nullable Long accountId,
                @Nullable String correlationId,
                @Nullable String detail) {
            return new TransitionRequest(next, eventType, actorKind, null, correlationId, detail, accountId);
        }
    }
}
