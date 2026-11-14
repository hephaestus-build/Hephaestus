package de.tum.cit.aet.hephaestus.integration.outline.connect;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionCommands;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient.OutlineIdentity;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiException;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineTokenClient;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Admin surface behind the Outline connection endpoints, scoped to the kind-specific credential
 * concern (the token health probe and the key replacement). The health snapshot and manual "sync now" trigger live in the
 * unified sync-observability API: {@code OutlineConnectionSyncStateProvider} (read) and
 * {@code OutlineIntegrationSyncRunner} (manual trigger); the per-connection job guard in
 * {@code SyncJobService} owns in-flight de-duplication.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineConnectionAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutlineConnectionAdminService.class);

    private final ConnectionService connectionService;
    private final ConnectionCommands connectionCommands;
    private final OutlineTokenClient apiClient;
    private final TransactionTemplate transactionTemplate;

    public OutlineConnectionAdminService(
            ConnectionService connectionService,
            ConnectionCommands connectionCommands,
            OutlineTokenClient apiClient,
            TransactionTemplate transactionTemplate) {
        this.connectionService = connectionService;
        this.connectionCommands = connectionCommands;
        this.apiClient = apiClient;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Stores a new API key on the workspace's existing Outline connection. The collections, the mirrored documents,
     * the configuration and the change subscription stay. Outline must accept the key, and the key must belong to the
     * connected team: a key of another team would read another wiki into this mirror. The key is checked before the
     * write, outside its transaction, because the check calls Outline.
     */
    public OutlineTokenStatusDTO replaceToken(long workspaceId, String token, long actorAccountId) {
        Connection connection = requireActiveConnection(workspaceId);
        String serverUrl =
                Objects.requireNonNull(((ConnectionConfig.OutlineConfig) connection.getConfig()).serverUrl());
        OutlineIdentity identity;
        try {
            identity = apiClient.validateToken(serverUrl, token);
        } catch (OutlineApiException e) {
            int status = e.getCause() instanceof WebClientResponseException wire
                    ? wire.getStatusCode().value()
                    : 0;
            if (status == 401) {
                throw new IllegalArgumentException(
                        "Outline does not accept this key. Check that you copied all of it.");
            }
            if (status == 403) {
                throw new IllegalArgumentException(
                        "Outline refused this key for reading. Give the key read access to the API.");
            }
            throw e;
        }
        if (!identity.teamId().equals(connection.getInstanceKey())) {
            throw new IllegalArgumentException(
                    "This key belongs to another Outline team. Use a key of the connected team.");
        }
        long validatedId = Objects.requireNonNull(connection.getId());
        String teamId = identity.teamId();
        transactionTemplate.executeWithoutResult(status -> {
            Connection replaced = connectionService
                    .rotateBearerToken(workspaceId, IntegrationKind.OUTLINE, new BearerToken(token, null))
                    .orElseThrow(() -> new EntityNotFoundException("Outline connection", Long.toString(workspaceId)))
                    .connection();
            // The key was checked against the connection read before the call to Outline. Another admin may have
            // reconnected another team since; the throw rolls the write back.
            if (!Objects.equals(replaced.getId(), validatedId) || !teamId.equals(replaced.getInstanceKey())) {
                throw new IllegalArgumentException(
                        "The Outline connection changed while Hephaestus checked the key. Reload the page and try again.");
            }
            connectionCommands.recordAdministrativeChange(
                    replaced,
                    "REPLACE_CREDENTIALS",
                    actorAccountId,
                    // The idempotency index treats an absent correlation id as one value, so each change carries its
                    // own.
                    UUID.randomUUID().toString(),
                    "Outline API key replaced");
        });
        log.info("outline.admin: replaced the API key of workspaceId={}", workspaceId);
        // Outline accepted the key a moment ago; a second probe could only turn a passing outage into "refused".
        return acceptedTokenStatus(workspaceId, serverUrl, token);
    }

    /**
     * Probes the stored API token against Outline: {@code auth.info} answers whether the token is still
     * accepted at all, and {@code apiKeys.list} — when the token may see its own key — adds the name,
     * expiry and last-use Outline keeps for it. A rejected token yields {@code accepted=false} rather
     * than an error: "your token no longer works" is exactly the answer the admin card came to ask.
     */
    public OutlineTokenStatusDTO tokenStatus(long workspaceId) {
        Connection connection = requireActiveConnection(workspaceId);
        ConnectionConfig.OutlineConfig config = (ConnectionConfig.OutlineConfig) connection.getConfig();
        String serverUrl = Objects.requireNonNull(config.serverUrl(), "Outline connection must have a server URL");
        String token = connectionService
                .findActiveBearerToken(workspaceId, IntegrationKind.OUTLINE)
                .map(BearerToken::token)
                .orElse(null);
        if (token == null) {
            return new OutlineTokenStatusDTO(false, null, null, null, null);
        }
        try {
            apiClient.validateToken(serverUrl, token);
        } catch (OutlineApiException e) {
            log.debug("outline.admin: token probe rejected for workspaceId={}: {}", workspaceId, e.toString());
            return new OutlineTokenStatusDTO(false, null, null, null, null);
        }
        return acceptedTokenStatus(workspaceId, serverUrl, token);
    }

    /** The state of a key that Outline accepts, with the metadata that Outline lets the key read about itself. */
    private OutlineTokenStatusDTO acceptedTokenStatus(long workspaceId, String serverUrl, String token) {
        try {
            return apiClient
                    .describeToken(serverUrl, token)
                    .map(d -> new OutlineTokenStatusDTO(true, d.name(), d.last4(), d.expiresAt(), d.lastActiveAt()))
                    .orElseGet(() -> new OutlineTokenStatusDTO(true, null, null, null, null));
        } catch (OutlineApiException e) {
            // The token is accepted (auth.info passed); only the metadata probe faltered — a flaky
            // apiKeys.list must not turn a healthy token into a 502. Treat it like the 403 case:
            // token accepted, metadata unavailable.
            log.debug(
                    "outline.admin: token accepted but metadata probe failed for workspaceId={}: {}",
                    workspaceId,
                    e.toString());
            return new OutlineTokenStatusDTO(true, null, null, null, null);
        }
    }

    /** The workspace's ACTIVE Outline connection, or a 404 when not connected — see {@link OutlineConnectionResolver}. */
    private Connection requireActiveConnection(long workspaceId) {
        return OutlineConnectionResolver.requireActiveConnection(connectionService, workspaceId);
    }
}
