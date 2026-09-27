package de.tum.cit.aet.hephaestus.integration.outline.connect;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.OutlineOriginPolicy;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineApiClient.OutlineIdentity;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineTokenClient;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentEventRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.lifecycle.OutlineWebhookRegistrar;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outline connection lifecycle strategy.
 *
 * <p>Outline uses an API-token paste flow (no OAuth round-trip). The admin enters:
 * <ul>
 *   <li>{@code server_url} — Outline Cloud {@code https://app.getoutline.com} or a self-hosted host
 *   <li>{@code token} — an Outline API token (a dedicated bot-user token is recommended)
 * </ul>
 *
 * <p>{@link #initiate} validates the token by calling {@code auth.info} through the SSRF-guarded
 * client and returns {@link ConnectInitiation.AcceptInline} immediately — the resolved team id
 * becomes the Connection's instance key, so a workspace can connect more than one Outline instance.
 * There is no vendor redirect, so {@link #finalizeConnect} is not applicable.
 *
 * <p>Disconnect is a GDPR erase, not just a state flip: {@link #eraseLocalData} removes the workspace's
 * mirrored documents so no cached bodies outlive the connection (the workspace-purge path erases the
 * same rows for the full teardown), and {@link #revokeProvider} deletes the change-notification
 * subscription. The Outline API token itself is revoked from the owner's settings.
 */
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class OutlineConnectionStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(OutlineConnectionStrategy.class);

    static final String INPUT_SERVER_URL = "server_url";
    static final String INPUT_TOKEN = "token";

    private final OutlineTokenClient outlineApiClient;
    private final OutlineWebhookRegistrar webhookRegistrar;
    private final OutlineDocumentRepository outlineDocumentRepository;
    private final OutlineCollectionRepository outlineCollectionRepository;
    private final OutlineDocumentEventRepository outlineDocumentEventRepository;
    private final OutlineOriginPolicy originPolicy;

    public OutlineConnectionStrategy(
            OutlineTokenClient outlineApiClient,
            OutlineWebhookRegistrar webhookRegistrar,
            OutlineDocumentRepository outlineDocumentRepository,
            OutlineCollectionRepository outlineCollectionRepository,
            OutlineDocumentEventRepository outlineDocumentEventRepository,
            OutlineOriginPolicy originPolicy) {
        this.outlineApiClient = outlineApiClient;
        this.webhookRegistrar = webhookRegistrar;
        this.outlineDocumentRepository = outlineDocumentRepository;
        this.outlineCollectionRepository = outlineCollectionRepository;
        this.outlineDocumentEventRepository = outlineDocumentEventRepository;
        this.originPolicy = originPolicy;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        Map<String, String> userInput = request.userInput();
        if (userInput == null) {
            throw new IllegalArgumentException("Outline connect requires 'server_url' and 'token'");
        }
        String serverUrl = userInput.get(INPUT_SERVER_URL);
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalArgumentException("Missing required field: '" + INPUT_SERVER_URL + "'");
        }
        if (!originPolicy.allows(serverUrl)) {
            throw new IllegalArgumentException("Outline origin is not approved by the instance operator");
        }
        String token = userInput.get(INPUT_TOKEN);
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Missing required field: '" + INPUT_TOKEN + "'");
        }
        // Validates the token AND the (admin-supplied) server URL through the SSRF guard before anything
        // is persisted. A bad token or unreachable/blocked host throws, surfacing a structured error.
        OutlineIdentity identity = outlineApiClient.validateToken(serverUrl, token);
        log.info("Outline connect: validated token for team={} on {}", identity.teamId(), serverUrl);
        // The token is held in memory only for this call; the caller encrypts and persists it.
        CredentialBundle bundle = new BearerToken(token, null);
        return new ConnectInitiation.AcceptInline(bundle, identity.teamId());
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        return new ConnectFinalization.Failed(
                "Outline uses API-token paste — finalizeConnect is not applicable; use initiate() output directly");
    }

    @Override
    @Transactional
    public void eraseLocalData(IntegrationRef ref) {
        long erased = outlineDocumentRepository.deleteByWorkspaceId(ref.workspaceId());
        long collections = outlineCollectionRepository.deleteByWorkspaceId(ref.workspaceId());
        long events = outlineDocumentEventRepository.deleteByWorkspaceId(ref.workspaceId());
        if (erased > 0 || collections > 0 || events > 0) {
            log.info(
                    "outline.audit: disconnect erase — actor={} erased {} mirrored document(s), {} collection registration(s) and {} document event(s) for workspace={}",
                    LoggingUtils.sanitizeForLog(
                            SecurityUtils.getCurrentUserLogin().orElse("system")),
                    erased,
                    collections,
                    events,
                    ref.workspaceId());
        }
    }

    @Override
    public void revokeProvider(IntegrationRef ref) {
        if (ref.connectionId() == null) {
            throw new IllegalArgumentException("Outline provider teardown requires a connection id");
        }
        webhookRegistrar.deregisterStrict(ref.workspaceId(), ref.connectionId());
    }
}
