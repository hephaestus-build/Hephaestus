package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation.RedirectToVendor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.InitiateRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

@Tag("unit")
class SlackConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SlackOAuthClient oauthClient;

    @Mock
    private SlackCredentialProvider credentialProvider;

    @Mock
    private SlackWorkspaceContentEraser workspaceContentEraser;

    private SlackConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SlackConnectionStrategy(
                oauthStateService,
                connectionService,
                oauthClient,
                credentialProvider,
                workspaceContentEraser,
                "client-id",
                "https://app.test/oauth/callback/slack");
    }

    private static IntegrationRef ref() {
        return new IntegrationRef(IntegrationKind.SLACK, 42L, null);
    }

    @Test
    void finalize_oauthClientThrows_returnsFailedWithErrorMessage() {
        when(oauthClient.exchangeCode(eq("c1"), any())).thenThrow(new SlackOAuthException("invalid_code"));

        ConnectFinalization r = strategy.finalizeConnect(ref(), Map.of("code", "c1"));

        assertThat(r).isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(((ConnectFinalization.Failed) r).reason()).contains("invalid_code");
    }

    @Test
    void finalize_rotationFields_rejectsWithExplicitMessage() {
        when(oauthClient.exchangeCode(eq("c"), any()))
                .thenReturn(new OAuthV2Access(
                        true,
                        null,
                        "xoxe-abc",
                        new OAuthV2Access.Team("T1", "Acme"),
                        /* expiresIn */ 43200,
                        /* refreshToken */ "xoxe-refresh"));

        ConnectFinalization r = strategy.finalizeConnect(ref(), Map.of("code", "c"));

        assertThat(r).isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(((ConnectFinalization.Failed) r).reason()).contains("Token rotation");
    }

    @Test
    void finalize_missingTeam_returnsFailed() {
        when(oauthClient.exchangeCode(eq("c"), any()))
                .thenReturn(new OAuthV2Access(true, null, "xoxb", null, null, null));

        ConnectFinalization r = strategy.finalizeConnect(ref(), Map.of("code", "c"));

        assertThat(r).isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(((ConnectFinalization.Failed) r).reason()).contains("missing team");
    }

    @Test
    void finalize_happyPath_returnsCompletedWithTeamConfigAndBearerToken() {
        when(oauthClient.exchangeCode(eq("c"), eq("https://app.test/oauth/callback/slack")))
                .thenReturn(new OAuthV2Access(
                        true, null, "xoxb-abc", new OAuthV2Access.Team("T9", "Hephaestus Test"), null, null));

        ConnectFinalization r = strategy.finalizeConnect(ref(), Map.of("code", "c"));

        assertThat(r).isInstanceOf(ConnectFinalization.Completed.class);
        ConnectFinalization.Completed c = (ConnectFinalization.Completed) r;
        assertThat(c.instanceKey()).isEqualTo("T9");
        assertThat(c.displayName()).isEqualTo("Hephaestus Test");
        assertThat(c.credentials()).isInstanceOf(BearerToken.class);
        assertThat(((BearerToken) c.credentials()).token()).isEqualTo("xoxb-abc");
        assertThat(c.config()).isInstanceOf(ConnectionConfig.SlackConfig.class);
        ConnectionConfig.SlackConfig cfg = (ConnectionConfig.SlackConfig) c.config();
        assertThat(cfg.teamId()).isEqualTo("T9");
        assertThat(cfg.teamName()).isEqualTo("Hephaestus Test");
        assertThat(cfg.notificationChannelId()).isNull();
        assertThat(cfg.teamLabel()).isNull();
    }

    @Test
    void initiate_buildsAuthorizeUrlWithLockedScopes() {
        // The initiating admin's actorRef must be woven into the OAuth state so the post-callback
        // connection audit row attributes the connect to them.
        when(oauthStateService.issue(42L, IntegrationKind.SLACK, "admin@example.com"))
                .thenReturn("state-abc");

        var initiation =
                strategy.initiate(new InitiateRequest(42L, IntegrationKind.SLACK, Map.of(), "admin@example.com"));

        verify(oauthStateService).issue(42L, IntegrationKind.SLACK, "admin@example.com");

        assertThat(initiation).isInstanceOf(RedirectToVendor.class);
        var redirect = (RedirectToVendor) initiation;
        String url = redirect.vendorUrl().toString();
        assertThat(url).startsWith("https://slack.com/oauth/v2/authorize?");
        assertThat(url).contains("chat%3Awrite");
        assertThat(url).doesNotContain("chat%3Awrite.public");
        assertThat(url).doesNotContain("team%3Aread");
        assertThat(url).contains("users%3Aread");
        assertThat(url).doesNotContain("users%3Aread.email");
        assertThat(url).contains("state=state-abc");
        assertThat(url).contains("client_id=client-id");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapp.test%2Foauth%2Fcallback%2Fslack");
    }

    @Test
    void eraseLocalData_erasesWorkspaceContentWithoutCallingSlack() {
        strategy.eraseLocalData(ref());

        verify(workspaceContentEraser).eraseWorkspace(42L);
        verifyNoInteractions(credentialProvider, oauthClient);
    }

    @Test
    void prepareProviderTeardown_keepsATeamStillUsedByAnotherConnection() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        when(connectionService.hasOtherInstalledConnection(ref)).thenReturn(true);

        assertThat(strategy.prepareProviderTeardown(ref)).isEmpty();
        verifyNoInteractions(credentialProvider, oauthClient);
    }

    @Test
    void prepareProviderTeardown_revokesTheReadTokenOnlyWhenRun() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        when(credentialProvider.resolve(ref)).thenReturn(Optional.of(new BearerToken("xoxb-tok", null)));

        Runnable teardown = strategy.prepareProviderTeardown(ref).orElseThrow();
        verifyNoInteractions(oauthClient);
        teardown.run();

        verify(oauthClient).revokeStrict("xoxb-tok");
        verifyNoInteractions(workspaceContentEraser);
    }

    @Test
    void prepareProviderTeardown_runPropagatesProviderFailure() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        when(credentialProvider.resolve(ref)).thenReturn(Optional.of(new BearerToken("xoxb-tok", null)));
        doThrow(new RuntimeException("slack unavailable")).when(oauthClient).revokeStrict("xoxb-tok");

        Runnable teardown = strategy.prepareProviderTeardown(ref).orElseThrow();

        assertThatThrownBy(teardown::run).hasMessage("slack unavailable");
    }

    @Test
    void initiate_withoutRedirectUri_failsFast() {
        SlackConnectionStrategy misconfigured = new SlackConnectionStrategy(
                oauthStateService,
                connectionService,
                oauthClient,
                credentialProvider,
                workspaceContentEraser,
                "client-id",
                "");

        assertThatThrownBy(() -> misconfigured.initiate(
                        new InitiateRequest(42L, IntegrationKind.SLACK, Map.of(), "admin@example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redirect URI");
    }
}
