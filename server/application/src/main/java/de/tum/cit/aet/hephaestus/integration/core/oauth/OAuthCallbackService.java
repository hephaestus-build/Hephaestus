package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Application-layer facade for the OAuth callback flow. Owns all repository access so
 * the {@link OAuthCallbackController} stays a thin HTTP adapter — required by the
 * {@code ArchitectureTest.controllersDoNotAccessRepositories} rule and the
 * {@code CodeQualityTest.controllersAreThin} 5-param ceiling.
 *
 * <p>This is intentionally a separate type from {@link ConnectionService}: that one
 * owns the state-machine + audit invariants and has many callers (credential
 * providers, lifecycle webhooks, purge contributor). This service owns the
 * OAuth-finalization-specific orchestration: find-or-create in-flight Connection,
 * stamp the vendor-side instance_key + display_name, persist credentials placeholder,
 * and transition PENDING → ACTIVE.
 */
@Service
public class OAuthCallbackService {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackService.class);

    /**
     * The kinds whose instance one workspace holds at a time: the states in which a connection holds it, the unique
     * index that settles a race the check here cannot, and the words that name it to the person connecting.
     */
    private record ExclusiveInstance(Set<IntegrationState> holdingStates, String index, String noun, String provider) {}

    private static final Map<IntegrationKind, ExclusiveInstance> EXCLUSIVE_INSTANCES = Map.of(
            IntegrationKind.SLACK,
            new ExclusiveInstance(
                    Set.of(IntegrationState.ACTIVE),
                    "uq_connection_one_active_slack_per_team",
                    "Slack workspace",
                    "Slack"),
            IntegrationKind.GITHUB,
            new ExclusiveInstance(
                    EnumSet.complementOf(EnumSet.of(IntegrationState.UNINSTALLED)),
                    "uq_connection_one_github_installation",
                    "GitHub App installation",
                    "GitHub"));

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final WorkspaceRepository workspaceRepository;
    private final CredentialBundleConverter credentialBundleConverter;
    private final AccountWorkspaceMembershipQuery membershipQuery;
    private final TransactionTemplate transactionTemplate;

    public OAuthCallbackService(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            WorkspaceRepository workspaceRepository,
            CredentialBundleConverter credentialBundleConverter,
            AccountWorkspaceMembershipQuery membershipQuery,
            PlatformTransactionManager transactionManager) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.workspaceRepository = workspaceRepository;
        this.credentialBundleConverter = credentialBundleConverter;
        this.membershipQuery = membershipQuery;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Resolve the Connection row that the in-flight OAuth should bind to. Look up an
     * existing PENDING row first; fall back to ACTIVE (credential refresh on reconnect);
     * create a fresh PENDING row only if neither exists. UNINSTALLED rows are
     * intentionally NOT reused — they're terminal; a new row is correct.
     */
    @Transactional
    public Connection findOrCreatePendingConnection(long workspaceId, IntegrationKind kind) {
        Optional<Connection> pending = connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                workspaceId, kind, IntegrationState.PENDING);
        if (pending.isPresent()) {
            return pending.get();
        }
        Optional<Connection> active = connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                workspaceId, kind, IntegrationState.ACTIVE);
        if (active.isPresent()) {
            return active.get();
        }
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(
                        () -> new EntityNotFoundException(
                                "We could not find that workspace. It may have been deleted. Reload the page to see what is current."));
        Connection fresh = new Connection(workspace, kind, /* instanceKey */ null, defaultConfig(kind));
        return connectionRepository.save(fresh);
    }

    /**
     * Finalize a successful OAuth flow: stamp instance_key + display_name, persist the
     * credential placeholder, and transition PENDING (or ACTIVE on reconnect) → ACTIVE
     * with an audit row attributed to {@code actorAccountId}.
     *
     * <p>Throws {@link InstanceConnectedElsewhereException} if another workspace holds the Slack
     * team or GitHub App installation, and {@link IllegalStateException} if the transition guard
     * rejects the move (e.g. the Connection was UNINSTALLED between create and finalize). The
     * controller translates both into HTTP 409.
     */
    public Connection completeConnection(
            Connection pending, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> complete(pending, completed, actorAccountId)));
        } catch (DataIntegrityViolationException e) {
            ExclusiveInstance exclusive = EXCLUSIVE_INSTANCES.get(pending.getKind());
            if (exclusive == null || !DataIntegrityViolationConstraints.hasName(e, exclusive.index())) {
                throw e;
            }
            // A concurrent connect of the same instance committed after this one's ownership check passed.
            String conflict = connectedElsewhere(
                            pending.getKind(), pending.getWorkspace().getId(), completed.instanceKey(), actorAccountId)
                    .orElseThrow(() -> e);
            throw new InstanceConnectedElsewhereException(conflict, e);
        }
    }

    private Connection complete(
            Connection pending, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        Connection connection = resolveCompletionTarget(pending, completed, actorAccountId);
        deleteSupersededPending(pending, connection);
        applyVendorMetadata(connection, completed);
        connection.setCredentials(completed.credentials(), credentialBundleConverter);
        connection = connectionRepository.save(connection);

        String correlationId = "oauth-" + completed.instanceKey() + "-" + UUID.randomUUID();
        connection = connectionService.transition(
                connection,
                TransitionRequest.byAccount(
                        IntegrationState.ACTIVE,
                        "OAUTH_COMPLETE",
                        "USER",
                        actorAccountId,
                        correlationId,
                        completed.displayName()));
        log.info(
                "OAuth complete: kind={} workspace={} connection={} instanceKey={} actor={}",
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getId(),
                sanitizeForLog(completed.instanceKey()),
                actorAccountId);
        return connection;
    }

    /**
     * The row to complete. A kind whose instance one workspace holds refuses an instance another workspace holds, and
     * reuses this workspace's own row for it rather than colliding with it on {@code (workspace, kind, instance_key)}.
     */
    private Connection resolveCompletionTarget(
            Connection connection, ConnectFinalization.Completed completed, @Nullable Long actorAccountId) {
        if (!EXCLUSIVE_INSTANCES.containsKey(connection.getKind())) {
            return connection;
        }
        String instanceKey = completed.instanceKey();
        if (instanceKey.isBlank()) {
            return connection;
        }
        long workspaceId = connection.getWorkspace().getId();
        Optional<String> conflict = connectedElsewhere(connection.getKind(), workspaceId, instanceKey, actorAccountId);
        if (conflict.isPresent()) {
            throw new InstanceConnectedElsewhereException(conflict.get(), null);
        }
        return connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspaceId, connection.getKind(), instanceKey)
                .filter(existing -> !Objects.equals(existing.getId(), connection.getId()))
                .orElse(connection);
    }

    /**
     * Why {@code instanceKey} cannot be connected to {@code workspaceId}, if another workspace holds it. The holder is
     * named only to an administrator of it; the log names it for the operator.
     */
    private Optional<String> connectedElsewhere(
            IntegrationKind kind, long workspaceId, String instanceKey, @Nullable Long actorAccountId) {
        ExclusiveInstance exclusive = EXCLUSIVE_INSTANCES.get(kind);
        if (exclusive == null) {
            return Optional.empty();
        }
        return connectionRepository
                .findAllByKindAndInstanceKeyAndStateIn(kind, instanceKey, exclusive.holdingStates())
                .stream()
                .map(Connection::getWorkspace)
                .filter(owner -> owner.getId() != workspaceId)
                .findFirst()
                .map(owner -> {
                    log.warn(
                            "Rejected {} connect: instanceKey={} is held by workspace={}, target workspace={}",
                            kind,
                            sanitizeForLog(instanceKey),
                            owner.getId(),
                            workspaceId);
                    return administers(owner.getId(), actorAccountId)
                            ? "This " + exclusive.noun() + " is already connected to the Hephaestus workspace \""
                                    + owner.getDisplayName() + "\" (" + owner.getWorkspaceSlug()
                                    + "). Disconnect " + exclusive.provider() + " there before you connect it here."
                            : "This " + exclusive.noun() + " is already connected to another Hephaestus workspace."
                                    + " A workspace admin there must disconnect " + exclusive.provider()
                                    + " first.";
                });
    }

    /** The OAuth state's {@code actorAccountId} is the initiating account id; anything else is not an administrator. */
    private boolean administers(long workspaceId, @Nullable Long actorAccountId) {
        if (actorAccountId == null) {
            return false;
        }
        return membershipQuery.isAdministrator(workspaceId, actorAccountId);
    }

    private void deleteSupersededPending(Connection original, Connection target) {
        if (Objects.equals(original.getId(), target.getId())) {
            return;
        }
        if (original.getState() != IntegrationState.PENDING || original.getInstanceKey() != null) {
            return;
        }
        connectionRepository.delete(original);
    }

    /**
     * Per-kind empty config seed for newly-created PENDING rows. The strategy's
     * {@code finalizeConnect} may upgrade the config later — we just need a non-null
     * config so JPA doesn't reject the INSERT.
     */
    private static ConnectionConfig defaultConfig(IntegrationKind kind) {
        return switch (kind) {
            case GITHUB -> new ConnectionConfig.GitHubAppConfig(null, null, null, new HashSet<>());
            case GITLAB ->
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        new HashSet<>(),
                        null);
            case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, new HashSet<>());
            case OUTLINE -> new ConnectionConfig.OutlineConfig(null, null, null, new HashSet<>());
        };
    }

    private static void applyVendorMetadata(Connection connection, ConnectFinalization.Completed completed) {
        if (completed.instanceKey() != null) {
            connection.bindInstanceKey(completed.instanceKey());
        }
        if (completed.displayName() != null) {
            connection.setDisplayName(completed.displayName());
        }
        // Strategies that resolve vendor metadata during finalize (Slack team id/name)
        // hand back a config blob; null leaves the placeholder seeded by
        // findOrCreatePendingConnection in place.
        if (completed.config() != null) {
            connection.setConfig(refreshedConfig(connection, completed.config()));
        }
    }

    /**
     * Reauthorizing the Slack team an active or suspended connection already holds refreshes its credential and team
     * name only: the provider knows nothing of the workspace's retention or streams, so its defaults must not replace
     * them. An uninstalled row starts over, because disconnecting erased what those settings governed.
     */
    private static ConnectionConfig refreshedConfig(Connection connection, ConnectionConfig vendor) {
        if ((connection.getState() == IntegrationState.ACTIVE || connection.getState() == IntegrationState.SUSPENDED)
                && vendor instanceof ConnectionConfig.SlackConfig refreshed
                && connection.getConfig() instanceof ConnectionConfig.SlackConfig current
                && refreshed.teamId() != null
                && refreshed.teamId().equals(current.teamId())) {
            return new ConnectionConfig.SlackConfig(
                    refreshed.teamId(), refreshed.teamName(), current.retentionDays(), current.enabledStreams());
        }
        return vendor;
    }

    /** A Slack team or GitHub App installation that another workspace holds; the message is safe to show the caller. */
    static final class InstanceConnectedElsewhereException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        InstanceConnectedElsewhereException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
