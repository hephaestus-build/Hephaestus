package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
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
 * orchestration (list, lookup, audit projection, inline-credentials Connection
 * creation) and is only called from the REST surface.
 */
@ConditionalOnServerRole
@Service
public class ConnectionAdminService {

    private static final Logger log = LoggerFactory.getLogger(ConnectionAdminService.class);

    private final ConnectionRepository connectionRepository;
    private final ConnectionAuditRepository auditRepository;
    private final ConnectionService connectionService;
    private final WorkspaceRepository workspaceRepository;
    private final IntegrationManifestRegistry manifests;
    private final CredentialBundleConverter credentialConverter;

    public ConnectionAdminService(
            ConnectionRepository connectionRepository,
            ConnectionAuditRepository auditRepository,
            ConnectionService connectionService,
            WorkspaceRepository workspaceRepository,
            IntegrationManifestRegistry manifests,
            CredentialBundleConverter credentialConverter) {
        this.connectionRepository = connectionRepository;
        this.auditRepository = auditRepository;
        this.connectionService = connectionService;
        this.workspaceRepository = workspaceRepository;
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

    @Transactional(readOnly = true)
    public List<ConnectionAudit> auditForConnection(long connectionId, int limit) {
        return auditRepository.findByConnectionIdOrderByOccurredAtDesc(connectionId).stream()
                .limit(limit)
                .toList();
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
            String actorRef) {
        Workspace workspace = workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));

        ConnectionConfig config = buildConfigForInlineKind(kind, userInput, instanceKey);
        // Reconnect-friendly upsert: (workspace, kind, instance_key) is unique, so a previous
        // UNINSTALLED install of the same vendor instance is revived with fresh config +
        // credentials instead of colliding — the guarded INITIATE reconnect in
        // ConnectionService.transition allows the UNINSTALLED → ACTIVE revival.
        Connection connection = connectionRepository
                .findByWorkspaceIdAndKindAndInstanceKey(workspaceId, kind, instanceKey)
                .map(existing -> {
                    existing.setConfig(config);
                    return existing;
                })
                .orElseGet(() -> connectionRepository.save(new Connection(workspace, kind, instanceKey, config)));

        persistCredentials(connection, credentials);

        String correlationId = "initiate-" + connection.getId() + "-" + UUID.randomUUID();
        connection = connectionService.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE,
                        "INITIATE",
                        "ADMIN",
                        actorRef,
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
        log.debug(
                "Encrypted credentials for connection={} kind={} (bundle type={})",
                connection.getId(),
                connection.getKind(),
                bundle.getClass().getSimpleName());
    }

    /**
     * Per-kind config construction for inline-flow Connections. Only kinds with an
     * {@code AcceptInline} initiation are handled here — others should never reach
     * this branch because their strategies return {@code RedirectToVendor}.
     */
    private ConnectionConfig buildConfigForInlineKind(
            IntegrationKind kind, Map<String, String> userInput, @Nullable String instanceKey) {
        Set<String> enabledStreams = new HashSet<>();
        return switch (kind) {
            case GITHUB_ACCESS ->
                throw new IllegalArgumentException(
                        "Configure GitHub access through its organization-owner approval flow");
            case KEYCLOAK_DIRECTORY ->
                throw new IllegalArgumentException("Configure directory access from workspace member administration");
            case GITLAB -> {
                String serverUrl = userInput.getOrDefault("server_url", "https://gitlab.com");
                Long groupId = parseGroupId(instanceKey);
                yield new ConnectionConfig.GitLabConfig(
                        serverUrl,
                        groupId,
                        /* gitlabWebhookId */ null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        enabledStreams);
            }
            case GITHUB ->
                new ConnectionConfig.GitHubPatConfig(
                        /* orgLogin */ null, userInput.getOrDefault("server_url", null), enabledStreams);
            case SLACK ->
                new ConnectionConfig.SlackConfig(
                        instanceKey,
                        /* teamName */ null,
                        /* notificationChannelId */ null,
                        /* teamLabel */ null,
                        /* retentionDays */ null,
                        enabledStreams);
            case OUTLINE ->
                new ConnectionConfig.OutlineConfig(
                        userInput.getOrDefault("server_url", null),
                        /* webhookSubscriptionId */ null,
                        /* webhookSecret */ null,
                        enabledStreams);
        };
    }

    private static @Nullable Long parseGroupId(@Nullable String instanceKey) {
        if (instanceKey == null || instanceKey.isBlank()) return null;
        try {
            return Long.parseLong(instanceKey.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
