package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthPropertiesFixture;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSignInParameters;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientRegistry;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderService;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pins secure account-linking at the begin endpoint: link mode binds the CURRENT account from a
 * validated access cookie, rejects (no intent cookie written) when there is no valid session, and
 * link-only providers (Slack, Outline — classified by the login_provider row's TYPE, not by URL)
 * never begin a LOGIN.
 */
class AuthBeginControllerLinkTest extends BaseUnitTest {

    private static final String EXTENSION_ID = "ijkajblcbajjpjbknfgdiiiljipafiko";

    private LoginProviderService loginProviderService;
    private IdentityLinkAuthentication identityLinkAuthentication;
    private AuthIntentCookie authIntentCookie;
    private AuthBeginController controller;

    @BeforeEach
    void setUp() {
        loginProviderService = mock(LoginProviderService.class);
        lenient()
                .when(loginProviderService.findEnabled(any()))
                .thenReturn(Optional.of(providerRow("github", LoginProvider.ProviderType.GITHUB)));
        identityLinkAuthentication = mock(IdentityLinkAuthentication.class);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        authIntentCookie = new AuthIntentCookie(key);
        controller = buildController("");
    }

    private AuthBeginController buildController(String apiBasePath) {
        return new AuthBeginController(
                loginProviderService,
                authIntentCookie,
                identityLinkAuthentication,
                new InstalledClientRegistry(AuthPropertiesFixture.withBrowserExtensionIds(List.of(EXTENSION_ID))),
                AuthPropertiesFixture.withApiBasePath(apiBasePath));
    }

    private static LoginProvider providerRow(String registrationId, LoginProvider.ProviderType type) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        provider.setBaseUrl("https://example.com");
        provider.setEnabled(true);
        return provider;
    }

    private AuthIntentCookie.@Nullable Intent readIntent(MockHttpServletResponse res) {
        Cookie written = res.getCookie(AuthIntentCookie.COOKIE_NAME);
        if (written == null) {
            return null;
        }
        MockHttpServletRequest back = new MockHttpServletRequest();
        back.setCookies(written);
        return authIntentCookie.read(back);
    }

    @Test
    void link_withValidSession_stampsLinkingAccountId() {
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any())).thenReturn(42L);
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin("github", null, "/settings", "link", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/oauth2/authorization/github");
        AuthIntentCookie.Intent intent = readIntent(res);
        assertThat(intent).isNotNull();
        assertThat(intent.mode()).isEqualTo(AuthIntentCookie.Intent.Mode.LINK);
        assertThat(intent.linkingAccountId()).isEqualTo(42L);
    }

    @Test
    void link_unauthenticated_rejectedWithNoIntentCookie() {
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any())).thenReturn(null);
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin("github", null, "/settings", "link", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/auth/error?code=link_requires_auth");
        assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
    }

    @Test
    void link_withStaleSignIn_asksToConfirmAccessAndWritesNoIntent() {
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any()))
                .thenThrow(new StepUpRequiredException(Duration.ofMinutes(5)));
        MockHttpServletResponse res = new MockHttpServletResponse();
        String onboarding = "/w/intro/onboarding?returnTo=%2Fw%2Fintro%2Factivity&step=accounts";

        RedirectView view = controller.begin("github", null, onboarding, "link", new MockHttpServletRequest(), res);

        var page = UriComponentsBuilder.fromUriString(Objects.requireNonNull(view.getUrl()))
                .build();
        assertThat(page.getPath()).isEqualTo("/auth/error");
        assertThat(page.getQueryParams().getFirst("code")).isEqualTo("step_up_required");
        assertThat(URLDecoder.decode(
                        Objects.requireNonNull(page.getQueryParams().getFirst("returnTo")), StandardCharsets.UTF_8))
                .isEqualTo(onboarding);
        // No dance was started, so no intent may survive to be redeemed by a later callback.
        assertThat(readIntent(res)).isNull();
    }

    @Test
    void link_withStaleSignIn_dropsADestinationOffThisSite() {
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any()))
                .thenThrow(new StepUpRequiredException(Duration.ofMinutes(5)));

        RedirectView view = controller.begin(
                "github",
                null,
                "https://evil.example/settings",
                "link",
                new MockHttpServletRequest(),
                new MockHttpServletResponse());

        assertThat(view.getUrl()).isEqualTo("/auth/error?code=step_up_required");
    }

    @Test
    void unknownOrDisabledProvider_rejected() {
        when(loginProviderService.findEnabled("nope")).thenReturn(Optional.empty());
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin("nope", null, "/", "login", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/auth/error?code=unknown_provider");
        assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
    }

    /**
     * Link-only is classified by the login_provider row's TYPE: a self-hosted Outline's authorization URL is
     * indistinguishable from a GitLab's by shape.
     */
    @ParameterizedTest
    @EnumSource(
            value = LoginProvider.ProviderType.class,
            names = {"SLACK", "OUTLINE"})
    void shouldRejectLoginModeWhenTheProviderIsLinkOnly(LoginProvider.ProviderType type) {
        String slug = type.name().toLowerCase(Locale.ROOT);
        when(loginProviderService.findEnabled(slug)).thenReturn(Optional.of(providerRow(slug, type)));
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin(slug, null, "/settings", "login", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/auth/error?code=link_requires_auth");
        assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
        verifyNoInteractions(identityLinkAuthentication);
    }

    @Test
    void outlineLinkMode_withValidSession_proceedsToInit() {
        when(loginProviderService.findEnabled("outline"))
                .thenReturn(Optional.of(providerRow("outline", LoginProvider.ProviderType.OUTLINE)));
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any())).thenReturn(42L);
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin("outline", null, "/settings", "link", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/oauth2/authorization/outline");
        AuthIntentCookie.Intent intent = readIntent(res);
        assertThat(intent).isNotNull();
        assertThat(intent.mode()).isEqualTo(AuthIntentCookie.Intent.Mode.LINK);
        assertThat(intent.linkingAccountId()).isEqualTo(42L);
    }

    @Test
    void loginMode_neverTouchesDecoder() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = controller.begin("github", "ws", "/", "login", new MockHttpServletRequest(), res);

        assertThat(view.getUrl()).isEqualTo("/oauth2/authorization/github");
        AuthIntentCookie.Intent intent = readIntent(res);
        assertThat(intent).isNotNull();
        assertThat(intent.mode()).isEqualTo(AuthIntentCookie.Intent.Mode.LOGIN);
        verifyNoInteractions(identityLinkAuthentication);
    }

    @Test
    void initRedirect_carriesApiBasePath_soItLandsOnTheProxiedEndpointNotTheSpa() {
        RedirectView view = buildController("/api")
                .begin("github", "ws", "/", "login", new MockHttpServletRequest(), new MockHttpServletResponse());

        assertThat(view.getUrl()).isEqualTo("/api/oauth2/authorization/github");
    }

    private static final String CALLBACK = "https://" + EXTENSION_ID + ".chromiumapp.org/callback";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    private RedirectView beginClient(
            String provider,
            @Nullable String clientId,
            @Nullable String redirectUri,
            @Nullable String challenge,
            @Nullable String method,
            @Nullable String state,
            MockHttpServletResponse res) {
        return controller.beginClient(
                provider, new ClientSignInParameters(clientId, redirectUri, challenge, method, state), res);
    }

    @Test
    void shouldSealTheClientRequestAndStartTheProviderDanceWhenARegisteredClientBegins() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        RedirectView view = beginClient("github", EXTENSION_ID, CALLBACK, CHALLENGE, "S256", "st.ate-1_~", res);

        assertThat(view.getUrl()).isEqualTo("/oauth2/authorization/github");
        AuthIntentCookie.Intent intent = readIntent(res);
        assertThat(intent).isNotNull();
        assertThat(intent.mode()).isEqualTo(AuthIntentCookie.Intent.Mode.CLIENT);
        assertThat(intent.clientRequestOrNull())
                .isEqualTo(new AuthIntentCookie.Intent.ClientRequest(EXTENSION_ID, CALLBACK, CHALLENGE, "st.ate-1_~"));
    }

    @Test
    void shouldRenderTheServerErrorPageAndNeverRedirectWhenTheClientOrCallbackIsNotRegistered() {
        String otherId = "abcdefghijklmnopabcdefghijklmnop";
        for (String[] pair : new String[][] {
            {otherId, "https://" + otherId + ".chromiumapp.org/callback"},
            {EXTENSION_ID, "https://" + EXTENSION_ID + ".chromiumapp.org/other"},
            {EXTENSION_ID, "https://evil.example/callback"},
        }) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            RedirectView view = beginClient("github", pair[0], pair[1], CHALLENGE, "S256", "state", res);
            assertThat(view.getUrl()).isEqualTo("/auth/error?code=client_not_registered");
            assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
        }
        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertThat(beginClient("github", null, null, CHALLENGE, "S256", "state", missing)
                        .getUrl())
                .isEqualTo("/auth/error?code=client_not_registered");
    }

    @Test
    void shouldRefuseAtTheCallbackWhenPkceOrStateIsMalformed() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(beginClient("github", EXTENSION_ID, CALLBACK, CHALLENGE, "plain", "s1", res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=invalid_request&state=s1");
        assertThat(beginClient("github", EXTENSION_ID, CALLBACK, "short", "S256", "s1", res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=invalid_request&state=s1");
        assertThat(beginClient("github", EXTENSION_ID, CALLBACK, CHALLENGE, "S256", "bad state&x=1", res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=invalid_request");
        assertThat(beginClient("github", EXTENSION_ID, CALLBACK, CHALLENGE, "S256", "x".repeat(257), res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=invalid_request");
        assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
    }

    @Test
    void shouldRefuseLinkOnlyAndUnknownProvidersAtTheCallback() {
        when(loginProviderService.findEnabled("slack"))
                .thenReturn(Optional.of(providerRow("slack", LoginProvider.ProviderType.SLACK)));
        when(loginProviderService.findEnabled("nope")).thenReturn(Optional.empty());
        MockHttpServletResponse res = new MockHttpServletResponse();

        assertThat(beginClient("slack", EXTENSION_ID, CALLBACK, CHALLENGE, "S256", "s1", res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=link_requires_auth&state=s1");
        assertThat(beginClient("nope", EXTENSION_ID, CALLBACK, CHALLENGE, "S256", "s1", res)
                        .getUrl())
                .isEqualTo(CALLBACK + "?error=unknown_provider&state=s1");
        assertThat(res.getCookie(AuthIntentCookie.COOKIE_NAME)).isNull();
    }
}
