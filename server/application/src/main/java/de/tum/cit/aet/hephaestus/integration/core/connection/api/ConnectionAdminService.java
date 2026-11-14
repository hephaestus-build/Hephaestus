package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionCredentialsReplacedEvent;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application-layer facade for the Connection registry's admin operations. Owns all
 * direct repository access so the {@link ConnectionController} stays a thin HTTP
 * adapter — required by the {@code ArchitectureTest.controllersDoNotAccessRepositories}
 * rule and the {@code CodeQualityTest.controllersAreThin} 5-param ceiling.
 *
 * <p>This is intentionally a separate type from {@link ConnectionService}: that one
 * owns the state-machine + audit invariants (called from many places — credential
 * providers, lifecycle webhooks, purge contributor). This service owns admin-only
 * orchestration (list, lookup, inline-credentials Connection
 * creation) and is only called from the REST surface.
 */
@ConditionalOnServerRole
@Service
public class ConnectionAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAdminService.class);

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final ProviderResourceRepairs repairs;
    private final WorkspaceSummaryQuery workspaces;
    private final IntegrationManifestRegistry manifests;
    private final CredentialBundleConverter credentialConverter;
    private final ApplicationEventPublisher events;

    public ConnectionAdminService(
            ConnectionRepository connectionRepository,
            ConnectionService connectionService,
            WorkspaceSummaryQuery workspaces,
            IntegrationManifestRegistry manifests,
            CredentialBundleConverter credentialConverter,
            ProviderResourceRepairs repairs,
            ApplicationEventPublisher events) {
        this.events = events;
        this.repairs = repairs;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.workspaces = workspaces;
        this.manifests = manifests;
        this.credentialConverter = credentialConverter;
    }

    @Transactional(readOnly = true)
    public List<Connection> listForWorkspace(long workspaceId) {
        return connectionRepository.findByWorkspaceId(workspaceId);
    }

    /**
     * Resolve a Connection by id AND verify it belongs to the path workspaceId.
     * Throws {@link NoSuchElementException} on either branch — we intentionally do NOT
     * distinguish "no such id" from "id exists in another workspace" because the latter
     * would leak workspace boundaries to a probing attacker.
     */
    @Transactional(readOnly = true)
    public Connection findInWorkspaceOrThrow(long workspaceId, long id) {
        Connection connection = connectionRepository
                .findById(id)
                .orElseThrow(() -> new NoSuchElementException("Connection not found: id=" + id));
        Long actualWorkspaceId = connection.getWorkspace() == null
                ? null
                : connection.getWorkspace().getId();
        if (!Objects.equals(actualWorkspaceId, workspaceId)) {
            log.info(
                    "Cross-workspace read attempt: connection={} actualWorkspace={} requestedWorkspace={}",
                    id,
                    actualWorkspaceId,
                    workspaceId);
            throw new NoSuchElementException("Connection not found in workspace " + workspaceId + ": id=" + id);
        }
        return connection;
    }

    public IntegrationManifestRegistry manifests() {
        return manifests;
    }

    /**
     * Persist a Connection from an inline-credential flow and transition it ACTIVE.
     * Credentials are AES-GCM encrypted via {@link CredentialBundleConverter} before the
     * row reaches an ACTIVE state — the {@code credentials_encrypted} / {@code credentials_alg}
     * pair stays in lockstep through {@link Connection#setCredentials}.
     */
    @Transactional
    public Connection createInlineConnection(
            long workspaceId,
            IntegrationKind kind,
            @Nullable String instanceKey,
            CredentialBundle credentials,
            Map<String, String> userInput,
            @Nullable Long actorAccountId) {
        // Hold the workspace status steady while reconnecting.
        var workspace = (kind == IntegrationKind.GITLAB
                        ? workspaces.findStateForUpdate(workspaceId)
                        : workspaces.findState(workspaceId))
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));

        if (kind == IntegrationKind.GITLAB && !workspace.active()) {
            throw new IllegalArgumentException("Resume this workspace before reconnecting GitLab.");
        }

        if (kind == IntegrationKind.GITLAB
                && connectionRepository.findByWorkspaceId(workspaceId).stream()
                        .anyMatch(connection -> connection.getKind().family() == kind.family()
                                && connection.getState() != IntegrationState.UNINSTALLED)) {
            throw new IllegalArgumentException(
                    "Disconnect the current source-control integration before reconnecting GitLab.");
        }

        ConnectionConfig config = buildConfigForInlineKind(workspaceId, kind, userInput, instanceKey, credentials);
        repairs.requireAdoptable(new IntegrationRef(kind, workspaceId, instanceKey), config, credentials);
        // Reconnect-friendly upsert: (workspace, kind, instance_key) is unique, so a previous
        // UNINSTALLED install of the same vendor instance is revived with fresh config +
        // credentials instead of colliding — the guarded INITIATE reconnect in
        // ConnectionService.transition allows the UNINSTALLED → ACTIVE revival.
        Connection connection = connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspaceId, kind, instanceKey)
                .map(existing -> {
                    Connection locked = repairs.prepareCredentialCompletion(existing, config, credentials);
                    locked.setConfig(config);
                    return locked;
                })
                .orElseGet(() -> connectionRepository.save(new Connection(
                        Objects.requireNonNull(entityManager).getReference(Workspace.class, workspace.id()),
                        kind,
                        instanceKey,
                        config)));

        persistCredentials(connection, credentials);

        String correlationId = "initiate-" + connection.getId() + "-" + UUID.randomUUID();
        connection = connectionService.transition(
                connection,
                TransitionRequest.byAccount(
                        IntegrationState.ACTIVE,
                        "INITIATE",
                        "ADMIN",
                        actorAccountId,
                        correlationId,
                        "Inline credentials accepted"));
        log.info("Initiated inline Connection id={} workspace={} kind={}", connection.getId(), workspaceId, kind);
        return connection;
    }

    /**
     * Encrypt + persist the credential bundle alongside the Connection. Null bundle is a
     * no-op (some inline flows persist credentials in a follow-up step); encryption +
     * algorithm-tag bookkeeping lives in {@link Connection#setCredentials}.
     */
    private void persistCredentials(Connection connection, @Nullable CredentialBundle bundle) {
        if (bundle == null) {
            return;
        }
        connection.setCredentials(bundle, credentialConverter);
        events.publishEvent(new ConnectionCredentialsReplacedEvent(
                connection.getId(), connection.getWorkspace().getId(), connection.getKind()));
        log.debug(
                "Encrypted credentials for connection={} kind={} (bundle type={})",
                connection.getId(),
                connection.getKind(),
                bundle.getClass().getSimpleName());
    }

    /**
     * Per-kind config construction for inline-flow Connections. Only kinds with an
     * {@code AcceptInline} initiation are handled here. GitLab can revive only its previous group;
     * its strategy checked the fresh token before this transaction began.
     */
    private ConnectionConfig buildConfigForInlineKind(
            long workspaceId,
            IntegrationKind kind,
            Map<String, String> userInput,
            @Nullable String instanceKey,
            CredentialBundle freshCredentials) {
        Set<String> enabledStreams = new HashSet<>();
        return switch (kind) {
            case GITHUB -> throw new IllegalStateException(kind + " is never connected inline");
            case GITLAB -> {
                Connection previous = connectionRepository
                        .findByWorkspaceIdAndKindAndInstanceKey(workspaceId, kind, instanceKey)
                        .orElseThrow(() ->
                                new IllegalArgumentException("This workspace has no GitLab connection to reconnect."));
                long previousId = Objects.requireNonNull(previous.getId());
                if (!(previous.getConfig() instanceof ConnectionConfig.GitLabConfig before)) {
                    throw new IllegalStateException("The disconnected GitLab connection has no group configuration.");
                }
                Long expectedVersion = previous.getVersion();
                repairs.requireAdoptableChange(
                        previous, before.withTokenMetadata(null).withGitlabWebhookId(null), freshCredentials);
                connectionRepository.acquireLifecycleLock(previousId, workspaceId);
                Objects.requireNonNull(entityManager).refresh(previous);
                if (!Objects.equals(expectedVersion, previous.getVersion())) {
                    throw new TransientDataAccessResourceException("The GitLab connection changed before reconnecting");
                }
                if (previous.getState() != IntegrationState.UNINSTALLED) {
                    throw new IllegalArgumentException("This GitLab connection changed. Reload before reconnecting.");
                }
                ConnectionConfig current = previous.getConfig();
                if (!(current instanceof ConnectionConfig.GitLabConfig gitlab)) {
                    throw new IllegalStateException("The disconnected GitLab connection has no group configuration.");
                }
                yield gitlab.withTokenMetadata(null).withGitlabWebhookId(null);
            }
            case SLACK ->
                new ConnectionConfig.SlackConfig(
                        instanceKey, /* teamName */ null, /* retentionDays */ null, enabledStreams);
            case OUTLINE ->
                new ConnectionConfig.OutlineConfig(
                        userInput.getOrDefault("server_url", null),
                        /* webhookSubscriptionId */ null,
                        /* webhookRoute */ null,
                        enabledStreams);
        };
    }
}
