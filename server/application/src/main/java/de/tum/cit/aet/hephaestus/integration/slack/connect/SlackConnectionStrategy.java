package de.tum.cit.aet.hephaestus.integration.slack.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.OAuthExchangeRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    // Slack documents service errors that can follow a partial success. Only explicit refusals discard intent.
    private static final Set<String> REFUSED_EXCHANGES = Set.of(
            "invalid_code",
            "invalid_client_id",
            "bad_client_secret",
            "bad_redirect_uri",
            "access_denied",
            "invalid_client",
            "invalid_grant",
            "invalid_scope",
            "scope_not_allowed");

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
    private final OAuthExchangeRepair exchangeRepairs;
    private final SlackWorkspaceContentEraser workspaceContentEraser;
    private final String clientId;
    private final String scopes;
    private final String redirectUri;

    public SlackConnectionStrategy(
            OAuthStateService oauthStateService,
            SlackOAuthClient oauthClient,
            OAuthExchangeRepair exchangeRepairs,
            SlackWorkspaceContentEraser workspaceContentEraser,
            @Value("${hephaestus.integration.slack.client-id:}") String clientId,
            PublicApiUrls publicApiUrls) {
        this.oauthStateService = oauthStateService;
        this.oauthClient = oauthClient;
        this.exchangeRepairs = exchangeRepairs;
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
        UUID attempt = exchangeRepairs.begin(ref);
        boolean tokenReturned = false;
        try {
            OAuthV2Access response = oauthClient.exchangeCode(code, redirectUri);
            tokenReturned =
                    response.accessToken() != null && !response.accessToken().isBlank();
            if (response.team() == null || response.team().id() == null) {
                exchangeRepairs.uncertain(attempt);
                return new ConnectFinalization.Failed("The Slack OAuth response has no team.");
            }
            if (response.accessToken() == null || response.accessToken().isBlank()) {
                exchangeRepairs.uncertain(attempt);
                return new ConnectFinalization.Failed("The Slack OAuth response has no access_token.");
            }
            String fingerprint = ProviderResourceAdapter.slackTokenFingerprint(response.accessToken());
            exchangeRepairs.observed(attempt, response.team().id(), response.botUserId(), fingerprint);
            if (response.expiresIn() != null || response.refreshToken() != null) {
                exchangeRepairs.discard(attempt);
                return new ConnectFinalization.Failed("Hephaestus does not support token rotation yet.");
            }
            SlackOAuthClient.BotIdentity identity = oauthClient.botIdentity(response.accessToken());
            if (!response.team().id().equals(identity.teamId())) {
                exchangeRepairs.uncertain(attempt);
                return new ConnectFinalization.Failed("Slack returned a different team for the bot token.");
            }
            if (response.botUserId() != null && !response.botUserId().equals(identity.userId())) {
                exchangeRepairs.observed(attempt, response.team().id(), null, fingerprint);
                exchangeRepairs.uncertain(attempt);
                return new ConnectFinalization.Failed("Slack returned conflicting bot identities.");
            }
            exchangeRepairs.observed(attempt, response.team().id(), identity.userId(), fingerprint);
            ConnectionConfig.SlackConfig config = new ConnectionConfig.SlackConfig(
                    response.team().id(),
                    identity.userId(),
                    fingerprint,
                    response.team().name(),
                    null,
                    Set.of());
            return new ConnectFinalization.Completed(
                    response.team().id(),
                    new BearerToken(response.accessToken(), null),
                    response.team().name(),
                    config,
                    attempt);
        } catch (RuntimeException failure) {
            String reason = failure.getMessage();
            if (!tokenReturned
                    && failure instanceof SlackOAuthException
                    && reason != null
                    && REFUSED_EXCHANGES.contains(reason)) {
                exchangeRepairs.discard(attempt);
                return new ConnectFinalization.Failed(
                        "Hephaestus could not finish connecting to Slack: " + failure.getMessage());
            }
            exchangeRepairs.uncertain(attempt);
            throw failure;
        }
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
