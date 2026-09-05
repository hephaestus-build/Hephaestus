package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipal;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.stepup.StepUpRequiredException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ADR-0017 account-status gate in {@link HephaestusAuthSuccessHandler}: a SUSPENDED / DELETING /
 * DELETED account that re-authenticates gets NO cookie (see that class's onAuthenticationSuccess for
 * the full threat model). The decoder + JwtPrincipalFactory enforce the same invariant as defence-in-
 * depth, but this handler is where the login decision is made, so it must hold here independently.
 */
class HephaestusAuthSuccessHandlerTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");
    private static final String COOKIE_NAME = "__Host-HEPHAESTUS_AT";

    private AccountProvisioningService provisioningService;
    private HephaestusJwtIssuer jwtIssuer;
    private JwtPrincipalFactory principalFactory;
    private AuthIntentCookie authIntentCookie;
    private AuthEventWriter authEventWriter;
    private IdentityLinkAuthentication identityLinkAuthentication;
    private HephaestusAuthSuccessHandler handler;

    @BeforeEach
    void setUp() {
        provisioningService = mock(AccountProvisioningService.class);
        jwtIssuer = mock(HephaestusJwtIssuer.class);
        principalFactory = mock(JwtPrincipalFactory.class);
        authIntentCookie = mock(AuthIntentCookie.class);
        authEventWriter = mock(AuthEventWriter.class);
        identityLinkAuthentication = mock(IdentityLinkAuthentication.class);
        AuthProperties authProperties = mock(AuthProperties.class);
        lenient().when(authProperties.cookieName()).thenReturn(COOKIE_NAME);
        lenient().when(authProperties.sessionMaxLifetime()).thenReturn(Duration.ofHours(12));
        lenient().when(authIntentCookie.read(any())).thenReturn(null);

        handler = new HephaestusAuthSuccessHandler(
                provisioningService,
                jwtIssuer,
                principalFactory,
                authIntentCookie,
                authProperties,
                new AuthEventLogger(authEventWriter),
                identityLinkAuthentication,
                Clock.fixed(NOW, ZoneOffset.UTC),
                /* webappBaseUrl */ "");
    }

    @Test
    void successHandlerDoesNotOwnTransactionSoHandledProvisioningErrorsCanRedirect() throws Exception {
        var method = HephaestusAuthSuccessHandler.class.getMethod(
                "onAuthenticationSuccess",
                jakarta.servlet.http.HttpServletRequest.class,
                jakarta.servlet.http.HttpServletResponse.class,
                Authentication.class);

        Assertions.assertNull(method.getAnnotation(Transactional.class));
    }

    @ParameterizedTest
    @EnumSource(
            value = Account.Status.class,
            names = {"SUSPENDED", "DELETING", "DELETED"})
    void nonActiveAccountIsRefusedAtLoginWithNoCookie(Account.Status status) throws Exception {
        Account account = account(status);
        when(provisioningService.resolveOrProvision(any(), any(), any(), any())).thenReturn(provision(account, false));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("sub-1"));

        verify(jwtIssuer, never()).issue(any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=account_inactive");
        // A refused login must NOT be audited as a successful LOGIN.
        verify(authEventWriter, never()).write(any());
    }

    @Test
    void activeAccountMintsCookieAndRedirectsToValidatedReturnTo() throws Exception {
        Account account = account(Account.Status.ACTIVE);
        when(provisioningService.resolveOrProvision(any(), any(), any(), any())).thenReturn(provision(account, false));
        when(authIntentCookie.read(any())).thenReturn(AuthIntentCookie.Intent.login(null, "/teams"));
        JwtPrincipal principal = mock(JwtPrincipal.class);
        when(principalFactory.forAccount(account)).thenReturn(principal);
        when(jwtIssuer.issue(any(), any(), any()))
                .thenReturn(new HephaestusJwtIssuer.Token("minted-jwt", UUID.randomUUID(), NOW.plusSeconds(900)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("sub-1"));

        Cookie cookie = response.getCookie(COOKIE_NAME);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("minted-jwt");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getMaxAge()).isEqualTo(900);
        assertThat(response.getRedirectedUrl()).isEqualTo("/teams");

        // A successful (LOGIN-mode) authentication is audited as LOGIN/SUCCESS for the account.
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.LOGIN);
        assertThat(event.getValue().result()).isEqualTo(AuthEvent.Result.SUCCESS);
        assertThat(event.getValue().accountId()).isEqualTo(42L);
    }

    @Test
    void linkOutcomeIsAuditedAsIdentityLinkedNotLogin() throws Exception {
        // A genuine new-identity link (provisioning reports identityLinked=true) is IDENTITY_LINKED;
        // a LINK-mode re-affirm would report false and audit LOGIN instead (no phantom IDENTITY_LINKED).
        Account account = account(Account.Status.ACTIVE);
        when(provisioningService.resolveOrProvision(any(), any(), any(), any())).thenReturn(provision(account, true));
        when(principalFactory.forAccount(account)).thenReturn(mock(JwtPrincipal.class));
        when(jwtIssuer.issue(any(), any(), any()))
                .thenReturn(new HephaestusJwtIssuer.Token("minted-jwt", UUID.randomUUID(), NOW.plusSeconds(900)));

        handler.onAuthenticationSuccess(githubRequest(), new MockHttpServletResponse(), oauthToken("sub-1"));

        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.IDENTITY_LINKED);
        assertThat(event.getValue().result()).isEqualTo(AuthEvent.Result.SUCCESS);
    }

    @Test
    void linkModeAttachesTheIdentityWithoutMintingAFreshSession() throws Exception {
        Account account = account(Account.Status.ACTIVE);
        when(provisioningService.resolveOrProvision(any(), any(), any(), any())).thenReturn(provision(account, true));
        when(authIntentCookie.read(any())).thenReturn(AuthIntentCookie.Intent.link(42L, "/settings"));
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any())).thenReturn(42L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("sub-1"));

        // Linking is not a sign-in: no new cookie, so neither the session ceiling nor auth_time moves.
        verify(jwtIssuer, never()).issue(any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/settings");
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.IDENTITY_LINKED);
        assertThat(event.getValue().result()).isEqualTo(AuthEvent.Result.SUCCESS);
    }

    @Test
    void linkModeIsRefusedWhenTheCallbackLandsOnADifferentAccount() throws Exception {
        when(authIntentCookie.read(any())).thenReturn(AuthIntentCookie.Intent.link(42L, "/settings"));
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any())).thenReturn(99L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("sub-1"));

        // The identity must never be attached to whichever session happens to hold the browser now.
        verify(provisioningService, never()).resolveOrProvision(any(), any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=link_requires_auth");
        ArgumentCaptor<AuthEventData> event = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AuthEvent.EventType.IDENTITY_LINKED);
        assertThat(event.getValue().result()).isEqualTo(AuthEvent.Result.FAILURE);
        assertThat(event.getValue().failureReason()).isEqualTo("link_requires_auth");
    }

    @Test
    void linkModeAsksForAFreshSignInWhenTheSessionIsStale() throws Exception {
        when(authIntentCookie.read(any())).thenReturn(AuthIntentCookie.Intent.link(42L, "/settings"));
        when(identityLinkAuthentication.resolveAuthenticatedAccountId(any()))
                .thenThrow(new StepUpRequiredException(Duration.ofMinutes(5)));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("sub-1"));

        verify(provisioningService, never()).resolveOrProvision(any(), any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=step_up_required");
    }

    @Test
    void identityAlreadyLinkedElsewhereRedirectsToAuthErrorWithoutCookie() throws Exception {
        when(provisioningService.resolveOrProvision(any(), any(), any(), any()))
                .thenThrow(new AccountLinkConflictException("github", "5898705", 5L));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("5898705"));

        verify(jwtIssuer, never()).issue(any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=identity_already_linked");
        verify(authEventWriter, never()).write(any());
    }

    @Test
    void linkOnlyProviderLoginRedirectsToAuthErrorWithoutCookie() throws Exception {
        when(provisioningService.resolveOrProvision(any(), any(), any(), any()))
                .thenThrow(new LinkOnlyProviderLoginException("slack"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken("U123"));

        verify(jwtIssuer, never()).issue(any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=link_requires_auth");
        verify(authEventWriter, never()).write(any());
    }

    @Test
    void nonOAuth2AuthenticationIsRejectedWithNoProvisioningAndNoCookie() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        var nonOauth = new UsernamePasswordAuthenticationToken(
                new User("u", "p", List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                "p",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));

        handler.onAuthenticationSuccess(githubRequest(), response, nonOauth);

        verify(provisioningService, never()).resolveOrProvision(any(), any(), any(), any());
        verify(jwtIssuer, never()).issue(any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=unexpected_auth_type");
    }

    @Test
    void blankSubjectIsRejectedBeforeProvisioning() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(githubRequest(), response, oauthToken(" "));

        verify(provisioningService, never()).resolveOrProvision(any(), any(), any(), any());
        assertThat(response.getCookie(COOKIE_NAME)).isNull();
        assertThat(response.getRedirectedUrl()).isEqualTo("/auth/error?code=no_subject");
    }

    private static Account account(Account.Status status) {
        Account account = new Account("Logging-In User");
        account.setId(42L);
        account.setStatus(status);
        return account;
    }

    private static AccountProvisioningService.ProvisionResult provision(Account account, boolean identityLinked) {
        return new AccountProvisioningService.ProvisionResult(account, identityLinked);
    }

    private static MockHttpServletRequest githubRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/login/oauth2/code/github");
        return request;
    }

    private static OAuth2AuthenticationToken oauthToken(String subject) {
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("id", subject, "login", "octocat"), "id");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "github");
    }
}
