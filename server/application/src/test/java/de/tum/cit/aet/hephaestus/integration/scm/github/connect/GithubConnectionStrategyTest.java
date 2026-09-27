package de.tum.cit.aet.hephaestus.integration.scm.github.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GithubConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private Connection connection;

    @Mock
    private GitHubAppTokenService appTokenService;

    @Mock
    private ScmWorkspaceContentEraser contentEraser;

    private GithubConnectionStrategy strategy() {
        return new GithubConnectionStrategy(
                "https://github.com/apps/heph/installations/new",
                "123",
                oauthStateService,
                connectionService,
                appTokenService,
                contentEraser);
    }

    @Test
    void initiate_weavesInitiatingAdminActorRefIntoTheOAuthState() {
        when(oauthStateService.issue(7L, IntegrationKind.GITHUB, "admin@example.com"))
                .thenReturn("state-xyz");

        strategy()
                .initiate(new ConnectionStrategy.InitiateRequest(
                        7L, IntegrationKind.GITHUB, Map.of(), "admin@example.com"));

        verify(oauthStateService).issue(7L, IntegrationKind.GITHUB, "admin@example.com");
    }

    @Test
    void eraseLocalData_erasesTheWorkspaceMirrorWithoutCallingGitHub() {
        strategy().eraseLocalData(new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L));

        verify(contentEraser).eraseWorkspaceScmMirror(7L);
        verifyNoInteractions(connectionService, appTokenService);
    }

    @Test
    void prepareProviderTeardown_uninstallsTheGitHubAppOnlyWhenRun() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L);
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(connection.getConfig()).thenReturn(new ConnectionConfig.GitHubAppConfig(4242L, null, null, Set.of()));

        Runnable teardown = strategy().prepareProviderTeardown(ref).orElseThrow();
        verifyNoInteractions(appTokenService);
        teardown.run();

        verify(appTokenService).deleteInstallation(4242L);
        verifyNoInteractions(contentEraser);
    }

    @Test
    void prepareProviderTeardown_patConnectionHasNoInstallationToDelete() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L);
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(connection.getConfig()).thenReturn(new ConnectionConfig.GitHubPatConfig("org", null, Set.of()));

        assertThat(strategy().prepareProviderTeardown(ref)).isEmpty();
    }

    @Test
    void prepareProviderTeardown_keepsAnInstallationStillUsedByAnotherConnection() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242");
        IntegrationRef resolvedRef = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L);
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(9L);
        when(connection.getInstanceKey()).thenReturn("4242");
        when(connectionService.hasOtherInstalledConnection(resolvedRef)).thenReturn(true);

        assertThat(strategy().prepareProviderTeardown(ref)).isEmpty();
        verifyNoInteractions(appTokenService, contentEraser);
    }

    @Test
    void prepareProviderTeardown_runPropagatesProviderFailure() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L);
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(connection.getConfig()).thenReturn(new ConnectionConfig.GitHubAppConfig(4242L, null, null, Set.of()));
        doThrow(new RuntimeException("github unavailable"))
                .when(appTokenService)
                .deleteInstallation(4242L);

        Runnable teardown = strategy().prepareProviderTeardown(ref).orElseThrow();

        assertThatThrownBy(teardown::run).hasMessage("github unavailable");
    }
}
