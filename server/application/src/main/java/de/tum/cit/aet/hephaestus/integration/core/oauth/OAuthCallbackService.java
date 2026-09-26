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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

    /** Marker used in audit rows when the state token didn't carry an actorRef. */
    static final String ACTOR_FALLBACK = "oauth-callback";

    private static final String ONE_ACTIVE_SLACK_CONNECTION_PER_TEAM = "uq_connection_one_active_slack_per_team";

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
                .orElseThrow(() -> new EntityNotFoundException("Workspace not found: id=" + workspaceId));
        Connection fresh = new Connection(workspace, kind, /* instanceKey */ null, defaultConfig(kind));
        return connectionRepository.save(fresh);
    }

    /**
     * Finalize a successful OAuth flow: stamp instance_key + display_name, persist the
     * credential placeholder, and transition PENDING (or ACTIVE on reconnect) → ACTIVE
     * with an audit row attributed to {@code actorRef}.
     *
     * <p>Throws {@link SlackTeamConnectedElsewhereException} if another workspace holds the Slack
     * team, and {@link IllegalStateException} if the transition guard rejects the move (e.g. the
     * Connection was UNINSTALLED between create and finalize). The controller translates both into
     * HTTP 409.
     */
    public Connection completeConnection(
            Connection pending, ConnectFinalization.Completed completed, @Nullable String actorRef) {
        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(status -> complete(pending, completed, actorRef)));
        } catch (DataIntegrityViolationException e) {
            String teamId = completed.instanceKey();
            if (teamId == null || !DataIntegrityViolationConstraints.hasName(e, ONE_ACTIVE_SLACK_CONNECTION_PER_TEAM)) {
                throw e;
            }
            // A concurrent install of the same team committed after this one's ownership check passed.
            String conflict = connectedElsewhere(pending.getWorkspace().getId(), teamId, actorRef)
                    .orElseThrow(() -> e);
            throw new SlackTeamConnectedElsewhereException(conflict, e);
        }
    }

    private Connection complete(
            Connection pending, ConnectFinalization.Completed completed, @Nullable String actorRef) {
        Connection connection = resolveSlackCompletionTarget(pending, completed, actorRef);
        deleteSupersededPending(pending, connection);
        applyVendorMetadata(connection, completed);
        connection.setCredentials(completed.credentials(), credentialBundleConverter);
        connection = connectionRepository.save(connection);

        String actor = actorRef != null ? actorRef : ACTOR_FALLBACK;
        String correlationId = "oauth-" + completed.instanceKey() + "-" + UUID.randomUUID();
        connection = connectionService.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE,
                        "OAUTH_COMPLETE",
                        "USER",
                        actor,
                        correlationId,
                        completed.displayName()));
        log.info(
                "OAuth complete: kind={} workspace={} connection={} instanceKey={} actor={}",
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getId(),
                sanitizeForLog(completed.instanceKey()),
                sanitizeForLog(actor));
        return connection;
    }

    private Connection resolveSlackCompletionTarget(
            Connection connection, ConnectFinalization.Completed completed, @Nullable String actorRef) {
        if (connection.getKind() != IntegrationKind.SLACK) {
            return connection;
        }
        String instanceKey = completed.instanceKey();
        if (instanceKey == null || instanceKey.isBlank()) {
            return connection;
        }
        long workspaceId = connection.getWorkspace().getId();
        Optional<String> conflict = connectedElsewhere(workspaceId, instanceKey, actorRef);
        if (conflict.isPresent()) {
            throw new SlackTeamConnectedElsewhereException(conflict.get(), null);
        }
        return connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspaceId, IntegrationKind.SLACK, instanceKey)
                .filter(existing -> !Objects.equals(existing.getId(), connection.getId()))
                .orElse(connection);
    }

    /**
     * Why Slack team {@code teamId} cannot be connected to {@code workspaceId}, if another workspace
     * holds it ACTIVE. The holder is named only to an administrator of it; the log names it for the operator.
     */
    private Optional<String> connectedElsewhere(long workspaceId, String teamId, @Nullable String actorRef) {
        return connectionRepository
                .findAllByKindAndInstanceKeyInAndState(IntegrationKind.SLACK, List.of(teamId), IntegrationState.ACTIVE)
                .stream()
                .map(Connection::getWorkspace)
                .filter(owner -> owner.getId() != workspaceId)
                .findFirst()
                .map(owner -> {
                    log.warn(
                            "Rejected Slack install: team={} is ACTIVE in workspace={}, target workspace={}",
                            sanitizeForLog(teamId),
                            owner.getId(),
                            workspaceId);
                    return administers(owner.getId(), actorRef)
                            ? "This Slack workspace is already connected to the Hephaestus workspace \""
                                    + owner.getDisplayName() + "\" (" + owner.getWorkspaceSlug()
                                    + "). Disconnect Slack there before connecting it here."
                            : "This Slack workspace is already connected to another Hephaestus workspace."
                                    + " An administrator of that workspace must disconnect Slack there first.";
                });
    }

    /** The OAuth state's {@code actorRef} is the initiating account id; anything else is not an administrator. */
    private boolean administers(long workspaceId, @Nullable String actorRef) {
        if (actorRef == null) {
            return false;
        }
        try {
            return membershipQuery.isAdministrator(workspaceId, Long.parseLong(actorRef));
        } catch (NumberFormatException e) {
            return false;
        }
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
                        new HashSet<>());
            case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, null, null, new HashSet<>());
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
            connection.setConfig(completed.config());
        }
    }

    /** A Slack team can be ACTIVE in only one workspace; the message is safe to show the caller. */
    static final class SlackTeamConnectedElsewhereException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SlackTeamConnectedElsewhereException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
