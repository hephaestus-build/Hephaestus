package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final WorkspaceRepository workspaceRepository;
    private final CredentialBundleConverter credentialBundleConverter;

    public OAuthCallbackService(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            WorkspaceRepository workspaceRepository,
            CredentialBundleConverter credentialBundleConverter) {
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.workspaceRepository = workspaceRepository;
        this.credentialBundleConverter = credentialBundleConverter;
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
     * <p>Throws {@link IllegalStateException} if the transition guard rejects the move
     * (e.g. the Connection was UNINSTALLED between create and finalize). The controller
     * translates that into HTTP 409.
     */
    @Transactional
    public Connection completeConnection(
            Connection pending, ConnectFinalization.Completed completed, @Nullable String actorRef) {
        Connection connection = resolveSlackCompletionTarget(pending, completed);
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

    private Connection resolveSlackCompletionTarget(Connection connection, ConnectFinalization.Completed completed) {
        if (connection.getKind() != IntegrationKind.SLACK) {
            return connection;
        }
        String instanceKey = completed.instanceKey();
        if (instanceKey == null || instanceKey.isBlank()) {
            return connection;
        }
        Optional<Connection> active = connectionRepository.findFirstByKindAndInstanceKeyAndState(
                IntegrationKind.SLACK, instanceKey, IntegrationState.ACTIVE);
        if (active.isEmpty() || Objects.equals(active.get().getId(), connection.getId())) {
            return connectionRepository
                    .findByWorkspaceIdAndKindAndInstanceKey(
                            connection.getWorkspace().getId(), IntegrationKind.SLACK, instanceKey)
                    .filter(existing -> !Objects.equals(existing.getId(), connection.getId()))
                    .orElse(connection);
        }
        Connection existing = active.get();
        if (Objects.equals(
                existing.getWorkspace().getId(), connection.getWorkspace().getId())) {
            return existing;
        }
        throw new IllegalStateException("Slack team is already connected to workspace "
                + existing.getWorkspace().getId()
                + "; disconnect it before connecting it to workspace "
                + connection.getWorkspace().getId());
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
            case KEYCLOAK_DIRECTORY ->
                throw new IllegalArgumentException("Directory connections do not use an OAuth callback");
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
}
