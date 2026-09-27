package de.tum.cit.aet.hephaestus.integration.slack.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Slack OAuth strategy. A rotating installation is rejected rather than stored: no refresh path exists,
 *  so accepting one would leave a connection that stops working at the first rotation. */
@ConditionalOnServerRole
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true", matchIfMissing = false)
public class SlackConnectionStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(SlackConnectionStrategy.class);

    private static final String AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize";

    // Exact Slack bot scopes used by current code and subscribed events. Channel reading still requires
    // workspace-admin channel activation and the per-member opt-out firewall.
    static final Set<String> DEFAULT_SCOPES = Set.of(
            "chat:write",
            "assistant:write",
            "im:history",
            "channels:history",
            "groups:history",
            "channels:read",
            "channels:join",
            "groups:read",
            "users:read");

    private final OAuthStateService oauthStateService;
    private final ConnectionService connectionService;
    private final SlackOAuthClient oauthClient;
    private final SlackCredentialProvider credentialProvider;
    private final SlackWorkspaceContentEraser workspaceContentEraser;
    private final String clientId;
    private final String scopes;
    private final String redirectUri;

    public SlackConnectionStrategy(
            OAuthStateService oauthStateService,
            ConnectionService connectionService,
            SlackOAuthClient oauthClient,
            SlackCredentialProvider credentialProvider,
            SlackWorkspaceContentEraser workspaceContentEraser,
            @Value("${hephaestus.integration.slack.client-id:}") String clientId,
            @Value("${hephaestus.integration.slack.redirect-uri:}") String redirectUri) {
        this.oauthStateService = oauthStateService;
        this.connectionService = connectionService;
        this.oauthClient = oauthClient;
        this.credentialProvider = credentialProvider;
        this.workspaceContentEraser = workspaceContentEraser;
        this.clientId = clientId;
        this.scopes = String.join(",", DEFAULT_SCOPES);
        this.redirectUri = redirectUri;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalStateException("Slack redirect URI must be configured");
        }
        String state = oauthStateService.issue(request.workspaceId(), IntegrationKind.SLACK, request.actorRef());
        StringBuilder url = new StringBuilder(AUTHORIZE_URL)
                .append('?')
                .append("client_id=")
                .append(enc(clientId))
                .append("&scope=")
                .append(enc(scopes))
                .append("&state=")
                .append(enc(state));
        url.append("&redirect_uri=").append(enc(redirectUri));
        return new ConnectInitiation.RedirectToVendor(URI.create(url.toString()), state);
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        String code = callbackParams == null ? null : callbackParams.get("code");
        if (code == null || code.isBlank()) {
            return new ConnectFinalization.Failed("missing code");
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            return new ConnectFinalization.Failed("Slack redirect URI must be configured");
        }
        OAuthV2Access r;
        try {
            r = oauthClient.exchangeCode(code, redirectUri());
        } catch (SlackOAuthException e) {
            log.warn("Slack OAuth exchange failed: workspaceId={}, error={}", ref.workspaceId(), e.getMessage());
            return new ConnectFinalization.Failed("slack oauth failed: " + e.getMessage());
        }
        if (r.expiresIn() != null || r.refreshToken() != null) {
            return new ConnectFinalization.Failed("Token rotation not yet supported");
        }
        if (r.team() == null || r.team().id() == null) {
            return new ConnectFinalization.Failed("oauth response missing team");
        }
        if (r.accessToken() == null || r.accessToken().isBlank()) {
            return new ConnectFinalization.Failed("oauth response missing access_token");
        }
        ConnectionConfig.SlackConfig config =
                new ConnectionConfig.SlackConfig(r.team().id(), r.team().name(), /* retentionDays */ null, Set.of());
        return new ConnectFinalization.Completed(
                r.team().id(), new BearerToken(r.accessToken(), null), r.team().name(), config);
    }

    @Override
    public void eraseLocalData(IntegrationRef ref) {
        workspaceContentEraser.eraseWorkspace(ref.workspaceId());
        log.info(
                "slack.audit: disconnect erase — cleared ingested Slack content + consent for workspace={}",
                ref.workspaceId());
    }

    @Override
    public Optional<Runnable> prepareProviderTeardown(IntegrationRef ref) {
        if (connectionService.hasOtherInstalledConnection(ref)) {
            return Optional.empty();
        }
        String token = credentialProvider
                .resolve(ref)
                .filter(BearerToken.class::isInstance)
                .map(bundle -> ((BearerToken) bundle).token())
                .orElseThrow(() -> new IllegalStateException("Slack bot token is unavailable"));
        // Token revocation does not remove the team-level Slack app installation.
        return Optional.of(() -> oauthClient.revokeStrict(token));
    }

    private String redirectUri() {
        return redirectUri == null ? "" : redirectUri;
    }

    private static String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
