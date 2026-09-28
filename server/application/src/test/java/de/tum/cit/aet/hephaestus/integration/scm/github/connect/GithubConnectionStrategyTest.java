package de.tum.cit.aet.hephaestus.integration.scm.github.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient.UserInstallation;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubUserAuthorizationException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class GithubConnectionStrategyTest extends BaseUnitTest {

    private static final IntegrationRef REF = new IntegrationRef(IntegrationKind.GITHUB, 7L, null, 9L);
    private static final String INSTALL_URL = "https://github.com/apps/heph/installations/new";

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private Connection connection;

    @Mock
    private GitHubAppTokenService appTokenService;

    @Mock
    private GitHubAppUserAuthorizationClient userAuthorization;

    @Mock
    private ScmWorkspaceContentEraser contentEraser;

    private GithubConnectionStrategy strategy() {
        return strategy(INSTALL_URL);
    }

    private GithubConnectionStrategy strategy(@Nullable String installationUrl) {
        return new GithubConnectionStrategy(
                new GitHubProperties(
                        new GitHubProperties.App(123, null, null, installationUrl, null, null),
                        new GitHubProperties.Meta(null)),
                oauthStateService,
                connectionService,
                appTokenService,
                userAuthorization,
                contentEraser);
    }

    @Test
    void shouldWeaveTheInitiatingAdministratorIntoTheStateWhenInitiating() {
        when(userAuthorization.isConfigured()).thenReturn(true);
        when(oauthStateService.issue(7L, IntegrationKind.GITHUB, "admin@example.com"))
                .thenReturn("state-xyz");

        var initiation = strategy()
                .initiate(new ConnectionStrategy.InitiateRequest(
                        7L, IntegrationKind.GITHUB, Map.of(), "admin@example.com"));

        assertThat(initiation)
                .isEqualTo(new ConnectionStrategy.ConnectInitiation.RedirectToVendor(
                        java.net.URI.create(INSTALL_URL + "?state=state-xyz"), "state-xyz"));
    }

    @Test
    void shouldRefuseToInitiateWhenTheAppCannotVerifyAnInstallation() {
        when(userAuthorization.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> strategy()
                        .initiate(new ConnectionStrategy.InitiateRequest(7L, IntegrationKind.GITHUB, Map.of(), "7")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");
        verifyNoInteractions(oauthStateService);
    }

    @Test
    void shouldRefuseToInitiateWhenNoInstallationUrlIsConfigured() {
        assertThatThrownBy(() -> strategy(null)
                        .initiate(new ConnectionStrategy.InitiateRequest(7L, IntegrationKind.GITHUB, Map.of(), "7")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("installation-url");
    }

    @Test
    void shouldConnectAnOrganizationInstallationWhenTheAuthorizingPersonOwnsTheOrganization() {
        givenAccessible(organizationInstallation(4242L, "acme"));
        when(userAuthorization.ownsOrganization("user-token", "acme")).thenReturn(true);

        ConnectFinalization result = strategy().finalizeConnect(REF, installCallback("4242"));

        assertThat(result)
                .isEqualTo(new ConnectFinalization.Completed(
                        "4242", null, "acme", new ConnectionConfig.GitHubAppConfig(4242L, "acme", null, Set.of())));
    }

    @Test
    void shouldConnectAPersonalInstallationWhenItIsTheAuthorizingPersonsAccount() {
        givenAccessible(new UserInstallation(4242L, "User", new UserInstallation.Account(31L, "octocat")));
        when(userAuthorization.userId("user-token")).thenReturn(31L);

        ConnectFinalization result = strategy().finalizeConnect(REF, installCallback("4242"));

        assertThat(result).isInstanceOf(ConnectFinalization.Completed.class);
        assertThat(((ConnectFinalization.Completed) result).instanceKey()).isEqualTo("4242");
    }

    @Test
    void shouldRefuseAForgedInstallationIdThatCarriesNoAuthorization() {
        ConnectFinalization result =
                strategy().finalizeConnect(REF, Map.of("installation_id", "4242", "setup_action", "install"));

        assertThat(result).isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(((ConnectFinalization.Failed) result).reason())
                .contains("must request user authorization during installation");
        verifyNoInteractions(userAuthorization);
    }

    @Test
    void shouldRefuseAnInstallationTheAuthorizingPersonCannotAccess() {
        when(userAuthorization.exchangeCode("code-1")).thenReturn("user-token");
        when(userAuthorization.findAccessibleInstallation("user-token", 4242L)).thenReturn(Optional.empty());

        ConnectFinalization result = strategy().finalizeConnect(REF, installCallback("4242"));

        assertThat(result)
                .isEqualTo(new ConnectFinalization.Failed(
                        "Your GitHub account cannot access this installation of the app."));
    }

    @Test
    void shouldRefuseAnOrganizationInstallationWhenTheAuthorizingPersonIsOnlyAMember() {
        givenAccessible(organizationInstallation(4242L, "acme"));
        when(userAuthorization.ownsOrganization("user-token", "acme")).thenReturn(false);

        ConnectFinalization result = strategy().finalizeConnect(REF, installCallback("4242"));

        assertThat(result)
                .isEqualTo(new ConnectFinalization.Failed(
                        "Only an owner of the GitHub account acme can connect its installation."));
    }

    @Test
    void shouldRefuseSomeoneElsesPersonalInstallationThatTheAuthorizingPersonCollaboratesOn() {
        givenAccessible(new UserInstallation(4242L, "User", new UserInstallation.Account(31L, "octocat")));
        when(userAuthorization.userId("user-token")).thenReturn(99L);

        assertThat(strategy().finalizeConnect(REF, installCallback("4242")))
                .isInstanceOf(ConnectFinalization.Failed.class);
    }

    @Test
    void shouldRefuseAnInstallationOnAnAccountThatIsNeitherAnOrganizationNorAPerson() {
        givenAccessible(new UserInstallation(4242L, "Enterprise", new UserInstallation.Account(5L, null)));

        assertThat(strategy().finalizeConnect(REF, installCallback("4242")))
                .isInstanceOf(ConnectFinalization.Failed.class);
        verify(userAuthorization, never()).ownsOrganization(anyString(), anyString());
    }

    @Test
    void shouldReportAnInstallationThatAwaitsAnOwnersApproval() {
        ConnectFinalization result =
                strategy().finalizeConnect(REF, Map.of("code", "code-1", "setup_action", "request"));

        assertThat(((ConnectFinalization.Failed) result).reason()).contains("has to approve the installation request");
        verifyNoInteractions(userAuthorization);
    }

    @Test
    void shouldRefuseAnInstallationIdThatIsNotANumber() {
        assertThat(strategy().finalizeConnect(REF, Map.of("code", "code-1", "installation_id", "4242abc")))
                .isInstanceOf(ConnectFinalization.Failed.class);
        verifyNoInteractions(userAuthorization);
    }

    @Test
    void shouldFailWithoutDetailWhenGitHubCannotConfirmTheAuthorization() {
        when(userAuthorization.exchangeCode("code-1"))
                .thenThrow(new GitHubUserAuthorizationException(
                        "GitHub refused the code exchange: bad_verification_code"));

        ConnectFinalization result = strategy().finalizeConnect(REF, installCallback("4242"));

        assertThat(result)
                .isEqualTo(new ConnectFinalization.Failed(
                        "GitHub could not confirm your access to this installation. Try connecting GitHub again."));
        verify(userAuthorization, never()).findAccessibleInstallation(anyString(), anyLong());
    }

    @Test
    void shouldTreatACallbackFromAnInstallationStartedOnGitHubAsProviderInitiated() {
        assertThat(strategy().isProviderInitiated(Map.of("installation_id", "4242", "setup_action", "install")))
                .isTrue();
        assertThat(strategy().isProviderInitiated(Map.of("code", "code-1"))).isFalse();
    }

    @Test
    void shouldEraseTheWorkspaceMirrorWithoutCallingGitHub() {
        strategy().eraseLocalData(new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L));

        verify(contentEraser).eraseWorkspaceScmMirror(7L);
        verifyNoInteractions(connectionService, appTokenService);
    }

    @Test
    void shouldUninstallTheGitHubAppOnlyWhenTheTeardownRuns() {
        IntegrationRef ref = givenConnection(new ConnectionConfig.GitHubAppConfig(4242L, null, null, Set.of()));

        Runnable teardown = strategy().prepareProviderTeardown(ref).orElseThrow();
        verifyNoInteractions(appTokenService);
        teardown.run();

        verify(appTokenService).deleteInstallation(4242L);
        verifyNoInteractions(contentEraser);
    }

    @Test
    void shouldNotUninstallAnInstallationTheConnectionNeverRecorded() {
        IntegrationRef ref = givenConnection(new ConnectionConfig.GitHubAppConfig(null, null, null, Set.of()));

        assertThat(strategy().prepareProviderTeardown(ref)).isEmpty();
        verifyNoInteractions(appTokenService);
    }

    @Test
    void shouldNotUninstallAnInstallationOtherThanTheOneTheConnectionHolds() {
        IntegrationRef ref = givenConnection(new ConnectionConfig.GitHubAppConfig(1111L, null, null, Set.of()));

        assertThat(strategy().prepareProviderTeardown(ref)).isEmpty();
        verifyNoInteractions(appTokenService);
    }

    @Test
    void shouldHaveNoInstallationToDeleteForATokenConnection() {
        IntegrationRef ref = givenConnection(new ConnectionConfig.GitHubPatConfig("org", null, Set.of()));

        assertThat(strategy().prepareProviderTeardown(ref)).isEmpty();
    }

    @Test
    void shouldKeepAnInstallationThatAnotherConnectionStillUses() {
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
    void shouldPropagateAProviderFailureWhenTheTeardownRuns() {
        IntegrationRef ref = givenConnection(new ConnectionConfig.GitHubAppConfig(4242L, null, null, Set.of()));
        doThrow(new RuntimeException("github unavailable"))
                .when(appTokenService)
                .deleteInstallation(4242L);

        Runnable teardown = strategy().prepareProviderTeardown(ref).orElseThrow();

        assertThatThrownBy(teardown::run).hasMessage("github unavailable");
    }

    private IntegrationRef givenConnection(ConnectionConfig config) {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITHUB, 7L, "4242", 9L);
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));
        when(connection.getId()).thenReturn(9L);
        when(connection.getInstanceKey()).thenReturn("4242");
        when(connection.getConfig()).thenReturn(config);
        return ref;
    }

    private void givenAccessible(UserInstallation installation) {
        when(userAuthorization.exchangeCode("code-1")).thenReturn("user-token");
        when(userAuthorization.findAccessibleInstallation("user-token", installation.id()))
                .thenReturn(Optional.of(installation));
    }

    private static UserInstallation organizationInstallation(long id, String login) {
        return new UserInstallation(id, "Organization", new UserInstallation.Account(77L, login));
    }

    private static Map<String, String> installCallback(String installationId) {
        return Map.of("code", "code-1", "installation_id", installationId, "setup_action", "install");
    }
}
