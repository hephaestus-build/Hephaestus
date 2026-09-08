package de.tum.cit.aet.hephaestus.integration.scm.github.credentials;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link ApiCredentialProvider}. Looks up the workspace's ACTIVE
 * {@code Connection} row via {@link ConnectionService#findActive} and maps the persisted
 * {@link ConnectionConfig} sub-type to a {@link ApiCredentialProvider.CredentialBundle}:
 * {@code GitHubAppConfig} → {@link ApiCredentialProvider.InstallationCredential}
 * (installation identity only; actual installation token is minted by
 * {@link GithubTokenRefresher}),
 * {@code GitHubPatConfig} → {@link ApiCredentialProvider.BearerToken} (decrypted from
 * the per-row credential blob via {@link de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader}).
 *
 * <p>Returns {@link Optional#empty()} when no Connection exists, the Connection is not
 * ACTIVE, the persisted config is not a GitHub variant, or the PAT row has no credential
 * blob yet. Callers treat empty as "no auth available" and either prompt for reconnect
 * or suspend retry — they MUST NOT fall through to anonymous API access.
 */
@Component
public class GithubCredentialProvider implements ApiCredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(GithubCredentialProvider.class);

    private final ConnectionService connectionService;
    private final CredentialReader credentialReader;
    private final GitHubAppTokenService appTokenService;

    public GithubCredentialProvider(
            ConnectionService connectionService,
            CredentialReader credentialReader,
            GitHubAppTokenService appTokenService) {
        this.connectionService = connectionService;
        this.credentialReader = credentialReader;
        this.appTokenService = appTokenService;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public Optional<CredentialBundle> resolve(IntegrationRef ref) {
        if (ref == null || ref.kind() != IntegrationKind.GITHUB) {
            return Optional.empty();
        }
        Optional<Connection> maybeConnection = connectionService.findActive(ref.workspaceId(), IntegrationKind.GITHUB);
        if (maybeConnection.isEmpty()) {
            return Optional.empty();
        }
        Connection connection = maybeConnection.get();
        if (connection.getState() != IntegrationState.ACTIVE) {
            return Optional.empty();
        }
        return switch (connection.getConfig()) {
            case ConnectionConfig.GitHubAppConfig appCfg -> {
                Long installationId = appCfg.installationId();
                if (installationId == null) {
                    log.warn(
                            "GitHub App Connection {} is ACTIVE but has no installationId — skipping credential resolution",
                            connection.getInstanceKey());
                    yield Optional.empty();
                }
                yield Optional.of(new InstallationCredential(
                        installationId, String.valueOf(appTokenService.getConfiguredAppId())));
            }
            case ConnectionConfig.GitHubPatConfig ignored -> {
                if (connection.getCredentialsEncrypted() == null) {
                    log.warn(
                            "GitHub PAT Connection {} has no credentials blob — cannot resolve token",
                            connection.getInstanceKey());
                    yield Optional.empty();
                }
                yield credentialReader.credentialsOf(connection);
            }
            case ConnectionConfig.GitLabConfig ignored -> Optional.empty();
            case ConnectionConfig.SlackConfig ignored -> Optional.empty();
            case ConnectionConfig.OutlineConfig ignored -> Optional.empty();
            case ConnectionConfig.KeycloakDirectoryConfig ignored -> Optional.empty();
        };
    }
}
