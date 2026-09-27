package de.tum.cit.aet.hephaestus.integration.scm.github.connect;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@ConditionalOnServerRole
@Component
public class GithubConnectionStrategy implements ConnectionStrategy {

    private static final String CALLBACK_PARAM_INSTALLATION_ID = "installation_id";
    private static final String CALLBACK_PARAM_STATE = "state";

    private final String installUrl;
    private final String appId;
    private final OAuthStateService oauthStateService;
    private final ConnectionService connectionService;
    private final GitHubAppTokenService appTokenService;
    private final ScmWorkspaceContentEraser contentEraser;

    public GithubConnectionStrategy(
            @Value(
                            "${hephaestus.integration.github.app.installation-url:${hephaestus.integration.github.app.install-url:}}")
                    String installUrl,
            @Value("${hephaestus.integration.github.app.id:}") String appId,
            OAuthStateService oauthStateService,
            ConnectionService connectionService,
            GitHubAppTokenService appTokenService,
            ScmWorkspaceContentEraser contentEraser) {
        this.installUrl = installUrl == null ? "" : installUrl.trim();
        this.appId = appId == null ? "" : appId.trim();
        this.oauthStateService = oauthStateService;
        this.connectionService = connectionService;
        this.appTokenService = appTokenService;
        this.contentEraser = contentEraser;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public ConnectInitiation initiate(InitiateRequest request) {
        if (installUrl.isEmpty()) {
            throw new IllegalStateException(
                    "hephaestus.integration.github.app.installation-url is not configured — cannot initiate GitHub App install");
        }
        String state = oauthStateService.issue(request.workspaceId(), IntegrationKind.GITHUB, request.actorRef());
        String separator = installUrl.contains("?") ? "&" : "?";
        URI vendorUrl = URI.create(
                installUrl + separator + CALLBACK_PARAM_STATE + "=" + URLEncoder.encode(state, StandardCharsets.UTF_8));
        return new ConnectInitiation.RedirectToVendor(vendorUrl, state);
    }

    @Override
    public ConnectFinalization finalizeConnect(IntegrationRef ref, Map<String, String> callbackParams) {
        if (callbackParams == null) {
            return new ConnectFinalization.Failed("missing callback params");
        }
        String installationIdRaw = callbackParams.get(CALLBACK_PARAM_INSTALLATION_ID);
        if (installationIdRaw == null || installationIdRaw.isBlank()) {
            return new ConnectFinalization.Failed("missing installation_id in callback");
        }
        long installationId;
        try {
            installationId = Long.parseLong(installationIdRaw.trim());
        } catch (NumberFormatException e) {
            return new ConnectFinalization.Failed("installation_id is not a valid long: " + installationIdRaw);
        }
        InstallationCredential credentials = new InstallationCredential(installationId, appId);
        return new ConnectFinalization.Completed(Long.toString(installationId), credentials, null);
    }

    @Override
    public void eraseLocalData(IntegrationRef ref) {
        contentEraser.eraseWorkspaceScmMirror(ref.workspaceId());
    }

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
        long installationId = Objects.requireNonNull(config.installationId());
        return Optional.of(() -> appTokenService.deleteInstallation(installationId));
    }
}
