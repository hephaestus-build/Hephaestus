package de.tum.cit.aet.hephaestus.integration.slack.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
            "im:history",
            "channels:history",
            "groups:history",
            "channels:read",
            "channels:join",
            "groups:read",
            "users:read");

    private final OAuthStateService oauthStateService;
    private final SlackOAuthClient oauthClient;
    private final SlackWorkspaceContentEraser workspaceContentEraser;
    private final String clientId;
    private final String scopes;
    private final String redirectUri;

    public SlackConnectionStrategy(
            OAuthStateService oauthStateService,
            SlackOAuthClient oauthClient,
            SlackWorkspaceContentEraser workspaceContentEraser,
            @Value("${hephaestus.integration.slack.client-id:}") String clientId,
            PublicApiUrls publicApiUrls) {
        this.oauthStateService = oauthStateService;
        this.oauthClient = oauthClient;
        this.workspaceContentEraser = workspaceContentEraser;
        this.clientId = clientId;
        this.scopes = String.join(",", DEFAULT_SCOPES);
        this.redirectUri = publicApiUrls.uri("/oauth/callback/slack").toASCIIString();
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.SLACK;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        if (clientId.isBlank()) {
            throw new IllegalStateException(
                    "Slack is not set up on this instance. Ask your instance operator to set it up.");
        }
        String state = oauthStateService.issue(request.workspaceId(), IntegrationKind.SLACK, request.actorAccountId());
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
            return new ConnectFinalization.Failed("Slack did not send an authorization code.");
        }
        OAuthV2Access r;
        try {
            r = oauthClient.exchangeCode(code, redirectUri);
        } catch (SlackOAuthException e) {
            // The admin sees Slack's reason in the finalization result; the log keeps the failure class.
            log.warn(
                    "Slack OAuth exchange failed: workspaceId={}, error={}",
                    ref.workspaceId(),
                    e.getClass().getSimpleName());
            return new ConnectFinalization.Failed("Hephaestus could not finish connecting to Slack: " + e.getMessage());
        }
        if (r.expiresIn() != null || r.refreshToken() != null) {
            return new ConnectFinalization.Failed("Hephaestus does not support token rotation yet.");
        }
        if (r.team() == null || r.team().id() == null) {
            return new ConnectFinalization.Failed("The Slack OAuth response has no team.");
        }
        if (r.accessToken() == null || r.accessToken().isBlank()) {
            return new ConnectFinalization.Failed("The Slack OAuth response has no access_token.");
        }
        SlackOAuthClient.BotIdentity identity = oauthClient.botIdentity(r.accessToken());
        if (!r.team().id().equals(identity.teamId())) {
            return new ConnectFinalization.Failed("Slack returned a different team for the bot token.");
        }
        ConnectionConfig.SlackConfig config = new ConnectionConfig.SlackConfig(
                r.team().id(),
                identity.userId(),
                ProviderResourceAdapter.slackTokenFingerprint(r.accessToken()),
                r.team().name(),
                null,
                Set.of());
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

    private static String enc(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
