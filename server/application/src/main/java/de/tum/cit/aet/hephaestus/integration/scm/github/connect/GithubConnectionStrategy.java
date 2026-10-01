package de.tum.cit.aet.hephaestus.integration.scm.github.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient.UserInstallation;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubUserAuthorizationException;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Connects a workspace to a GitHub App installation. The {@code installation_id} GitHub appends to the callback can be
 * forged, so it is only a claim: the App requests user authorization during installation, and the connect proves the
 * claim with the returned {@code code} — the installation must be one the authorizing person can access, on an account
 * they own. The signed state proves which workspace and administrator started the flow; this proves the installation.
 *
 * @see <a href="https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/about-the-setup-url">GitHub: the installation_id can be spoofed</a>
 */
@ConditionalOnServerRole
@Component
public class GithubConnectionStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GithubConnectionStrategy.class);

    private static final String CALLBACK_PARAM_CODE = "code";
    private static final String CALLBACK_PARAM_INSTALLATION_ID = "installation_id";
    private static final String CALLBACK_PARAM_SETUP_ACTION = "setup_action";
    private static final String CALLBACK_PARAM_STATE = "state";
    private static final String SETUP_ACTION_REQUEST = "request";

    private final @Nullable String installationUrl;
    private final OAuthStateService oauthStateService;
    private final ConnectionService connectionService;
    private final GitHubAppTokenService appTokenService;
    private final GitHubAppUserAuthorizationClient userAuthorization;
    private final ScmWorkspaceContentEraser contentEraser;

    public GithubConnectionStrategy(
            GitHubProperties gitHubProperties,
            OAuthStateService oauthStateService,
            ConnectionService connectionService,
            GitHubAppTokenService appTokenService,
            GitHubAppUserAuthorizationClient userAuthorization,
            ScmWorkspaceContentEraser contentEraser) {
        this.installationUrl = gitHubProperties.app().installationUrl();
        this.oauthStateService = oauthStateService;
        this.connectionService = connectionService;
        this.appTokenService = appTokenService;
        this.userAuthorization = userAuthorization;
        this.contentEraser = contentEraser;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        if (installationUrl == null) {
            throw new IllegalStateException(
                    "hephaestus.integration.github.app.installation-url is not configured — cannot initiate GitHub App install");
        }
        if (!userAuthorization.isConfigured()) {
            throw new IllegalStateException(
                    "hephaestus.integration.github.app.client-id and client-secret are not configured — cannot verify a GitHub App installation");
        }
        String state = oauthStateService.issue(request.workspaceId(), IntegrationKind.GITHUB, request.actorAccountId());
        String separator = installationUrl.contains("?") ? "&" : "?";
        URI vendorUrl = URI.create(installationUrl + separator + CALLBACK_PARAM_STATE + "="
                + URLEncoder.encode(state, StandardCharsets.UTF_8));
        return new ConnectInitiation.RedirectToVendor(vendorUrl, state);
    }

    @Override
    public boolean isProviderInitiated(Map<String, String> callbackParams) {
        // With user authorization during installation, GitHub sends every installer here — including one who
        // installed from GitHub or from the workspace wizard, where the installation webhook creates the workspace.
        return callbackParams.containsKey(CALLBACK_PARAM_SETUP_ACTION);
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        if (SETUP_ACTION_REQUEST.equals(callbackParams.get(CALLBACK_PARAM_SETUP_ACTION))) {
            return new ConnectFinalization.Failed("An owner of the GitHub account has to approve the installation"
                    + " request. Connect GitHub again once it is approved.");
        }
        Optional<Long> installationId = parseInstallationId(callbackParams.get(CALLBACK_PARAM_INSTALLATION_ID));
        if (installationId.isEmpty()) {
            return new ConnectFinalization.Failed("GitHub did not name the installation to connect.");
        }
        String code = callbackParams.get(CALLBACK_PARAM_CODE);
        if (code == null || code.isBlank()) {
            return new ConnectFinalization.Failed("GitHub did not confirm who installed the app, so the installation"
                    + " cannot be connected. The GitHub App must request user authorization during installation.");
        }
        try {
            return verify(ref, installationId.get(), code);
        } catch (GitHubUserAuthorizationException e) {
            log.warn(
                    "GitHub installation verification failed: workspaceId={}, installationId={}, error={}",
                    ref.workspaceId(),
                    installationId.get(),
                    e.getMessage());
            return new ConnectFinalization.Failed(
                    "GitHub could not confirm your access to this installation. Try connecting GitHub again.");
        }
    }

    private ConnectFinalization verify(IntegrationRef ref, long installationId, String code) {
        String userToken = userAuthorization.exchangeCode(code);
        Optional<UserInstallation> installation =
                userAuthorization.findAccessibleInstallation(userToken, installationId);
        if (installation.isEmpty()) {
            log.warn(
                    "Rejected GitHub installation the authorizing user cannot access: workspaceId={}, installationId={}",
                    ref.workspaceId(),
                    installationId);
            return new ConnectFinalization.Failed("Your GitHub account cannot access this installation of the app.");
        }
        UserInstallation.Account account = installation.get().account();
        String login = account == null ? null : account.login();
        if (account == null
                || login == null
                || !owns(userToken, installation.get().targetType(), account.id(), login)) {
            log.warn(
                    "Rejected GitHub installation the authorizing user does not own: workspaceId={}, installationId={}",
                    ref.workspaceId(),
                    installationId);
            return new ConnectFinalization.Failed("Only an owner of the GitHub account "
                    + (login == null ? "" : login + " ")
                    + "can connect its installation.");
        }
        ConnectionConfig.GitHubAppConfig config =
                new ConnectionConfig.GitHubAppConfig(installationId, login, /* serverUrl */ null, Set.of());
        return new ConnectFinalization.Completed(Long.toString(installationId), null, login, config);
    }

    /**
     * Binding an installation hands the workspace every repository it covers, which can be more than the authorizing
     * person may read, so seeing the installation is not enough: they must own the account it is installed on.
     */
    private boolean owns(String userToken, @Nullable String targetType, long accountId, String login) {
        return switch (targetType) {
            case "Organization" -> userAuthorization.ownsOrganization(userToken, login);
            case "User" -> userAuthorization.userId(userToken) == accountId;
            case null, default -> false;
        };
    }

    private static Optional<Long> parseInstallationId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim())).filter(id -> id > 0);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    @Override
    public void eraseLocalData(IntegrationRef ref) {
        contentEraser.eraseWorkspaceScmMirror(ref.workspaceId());
    }

    /**
     * Uninstalls the App only for a connection that recorded its installation, which the installation webhook and a
     * verified connect do. A row without one never proved it controls the installation behind its key.
     */
    @Override
    public Optional<Runnable> prepareProviderTeardown(IntegrationRef ref) {
        var connectionOpt = connectionService.findReferenced(ref);
        if (connectionOpt.isEmpty()) {
            return Optional.empty();
        }
        var connection = connectionOpt.get();
        var resolvedRef =
                new IntegrationRef(ref.kind(), ref.workspaceId(), connection.getInstanceKey(), connection.getId());
        if (connectionService.hasOtherInstalledConnection(resolvedRef)
                || !(connection.getConfig() instanceof ConnectionConfig.GitHubAppConfig config)) {
            return Optional.empty();
        }
        Long installationId = config.installationId();
        if (installationId == null || !installationId.toString().equals(connection.getInstanceKey())) {
            return Optional.empty();
        }
        return Optional.of(() -> appTokenService.deleteInstallation(installationId));
    }
}
