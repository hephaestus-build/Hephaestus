package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation.RedirectToVendor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.InitiateRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.springframework.web.util.UriComponentsBuilder;

@Tag("unit")
class SlackConnectionStrategyTest extends BaseUnitTest {

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private SlackOAuthClient oauthClient;

    @Mock
    private SlackWorkspaceContentEraser workspaceContentEraser;

    private SlackConnectionStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SlackConnectionStrategy(
                oauthStateService,
                connectionService,
                oauthClient,
                workspaceContentEraser,
                "client-id",
                new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create("https://app.test"), "/api")));
    }

    private static ConnectionStrategy.ProviderFootprint footprint(String token) {
        return new ConnectionStrategy.ProviderFootprint(
                new ConnectionConfig.SlackConfig("T1", null, null, Set.of()), new BearerToken(token, null));
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
        assertThat(((ConnectFinalization.Failed) r).reason()).contains("token rotation");
    }

    @Test
    void finalize_missingTeam_returnsFailed() {
        when(oauthClient.exchangeCode(eq("c"), any()))
                .thenReturn(new OAuthV2Access(true, null, "xoxb", null, null, null));

        ConnectFinalization r = strategy.finalizeConnect(ref(), Map.of("code", "c"));

        assertThat(r).isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(((ConnectFinalization.Failed) r).reason()).contains("has no team");
    }

    @Test
    void finalize_happyPath_returnsCompletedWithTeamConfigAndBearerToken() {
        when(oauthClient.exchangeCode(eq("c"), eq("https://app.test/api/oauth/callback/slack")))
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
    }

    @Test
    void initiate_buildsAuthorizeUrlWithLockedScopes() {
        // The initiating admin's actorRef must be woven into the OAuth state so the post-callback
        // connection audit row attributes the connect to them.
        when(oauthStateService.issue(42L, IntegrationKind.SLACK, 42L)).thenReturn("state-abc");

        var initiation = strategy.initiate(new InitiateRequest(42L, IntegrationKind.SLACK, Map.of(), 42L));

        verify(oauthStateService).issue(42L, IntegrationKind.SLACK, 42L);

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
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fapp.test%2Fapi%2Foauth%2Fcallback%2Fslack");
    }

    @Test
    void eraseLocalData_erasesWorkspaceContentWithoutCallingSlack() {
        strategy.eraseLocalData(ref());

        verify(workspaceContentEraser).eraseWorkspace(42L);
        verifyNoInteractions(oauthClient);
    }

    @Test
    void prepareProviderTeardown_keepsATeamStillUsedByAnotherConnection() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        when(connectionService.hasOtherInstalledConnection(ref)).thenReturn(true);

        assertThat(strategy.prepareProviderTeardown(ref, footprint("xoxb-tok"))).isEmpty();
        verifyNoInteractions(oauthClient);
    }

    @Test
    void prepareProviderTeardown_revokesTheReadTokenOnlyWhenRun() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        Runnable teardown =
                strategy.prepareProviderTeardown(ref, footprint("xoxb-tok")).orElseThrow();
        verifyNoInteractions(oauthClient);
        teardown.run();

        verify(oauthClient).revokeStrict("xoxb-tok");
        verifyNoInteractions(workspaceContentEraser);
    }

    @Test
    void prepareProviderTeardown_runPropagatesProviderFailure() {
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, 42L, "T1", 7L);
        doThrow(new RuntimeException("slack unavailable")).when(oauthClient).revokeStrict("xoxb-tok");

        Runnable teardown =
                strategy.prepareProviderTeardown(ref, footprint("xoxb-tok")).orElseThrow();

        assertThatThrownBy(teardown::run).hasMessage("slack unavailable");
    }

    @Test
    void initiate_withoutClientId_failsFast() {
        SlackConnectionStrategy misconfigured = new SlackConnectionStrategy(
                oauthStateService,
                connectionService,
                oauthClient,
                workspaceContentEraser,
                "",
                new PublicApiUrls(AuthPropertiesFixture.withApiBasePath("/api")));

        assertThatThrownBy(() -> misconfigured.initiate(new InitiateRequest(42L, IntegrationKind.SLACK, Map.of(), 42L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Slack is not set up on this instance");
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "https://api.example.test | /api | https://api.example.test/api/oauth/callback/slack",
                "https://api.example.test/old | /edge/api/ | https://api.example.test/edge/api/oauth/callback/slack",
                "http://localhost:8080 | '' | http://localhost:8080/oauth/callback/slack"
            },
            delimiter = '|')
    void shouldUseTheSameCanonicalCallbackForAuthorizationAndCodeExchange(
            String issuer, String prefix, String expected) {
        var callbackStrategy = new SlackConnectionStrategy(
                oauthStateService,
                connectionService,
                oauthClient,
                workspaceContentEraser,
                "client-id",
                new PublicApiUrls(AuthPropertiesFixture.withApiBase(URI.create(issuer), prefix)));
        when(oauthStateService.issue(42L, IntegrationKind.SLACK, 42L)).thenReturn("state");
        when(oauthClient.exchangeCode("code", expected)).thenThrow(new SlackOAuthException("invalid_code"));

        var initiation = callbackStrategy.initiate(new InitiateRequest(42L, IntegrationKind.SLACK, Map.of(), 42L));
        assertThat(initiation).isInstanceOfSatisfying(RedirectToVendor.class, redirect -> {
            String encoded = Objects.requireNonNull(UriComponentsBuilder.fromUri(redirect.vendorUrl())
                    .build()
                    .getQueryParams()
                    .getFirst("redirect_uri"));
            assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(expected);
        });
        assertThat(callbackStrategy.finalizeConnect(ref(), Map.of("code", "code")))
                .isInstanceOf(ConnectFinalization.Failed.class);
        verify(oauthClient).exchangeCode("code", expected);
    }
}
