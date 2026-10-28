package de.tum.cit.aet.hephaestus.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventData;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventWriter;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionService;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthSessionServiceTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-06-02T10:00:00Z");

    private static final Instant AUTH_TIME = NOW.minus(Duration.ofMinutes(3));

    private static final Instant SESSION_CEILING = NOW.plus(Duration.ofHours(6));

    private static final long ACCOUNT_ID = 42L;

    private IssuedJwtRepository issuedJwtRepository;
    private AccountRepository accountRepository;
    private HephaestusJwtIssuer jwtIssuer;
    private AuthEventWriter authEventWriter;
    private ClientSessionService clientSessionService;
    private SessionRevocation sessionRevocation;
    private SimpleMeterRegistry meterRegistry;
    private AuthSessionService service;

    @BeforeEach
    void setUp() {
        issuedJwtRepository = mock(IssuedJwtRepository.class);
        accountRepository = mock(AccountRepository.class);
        jwtIssuer = mock(HephaestusJwtIssuer.class);
        AuthProperties properties = mock(AuthProperties.class);
        authEventWriter = mock(AuthEventWriter.class);
        AuthEventLogger eventLogger = new AuthEventLogger(authEventWriter);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        meterRegistry = new SimpleMeterRegistry();

        lenient().when(properties.cookieName()).thenReturn("__Host-HEPHAESTUS_AT");
        lenient().when(properties.cookieSecure()).thenReturn(true);

        clientSessionService = mock(ClientSessionService.class);
        sessionRevocation = mock(SessionRevocation.class);
        service = new AuthSessionService(
                issuedJwtRepository,
                accountRepository,
                jwtIssuer,
                eventLogger,
                clientSessionService,
                sessionRevocation,
                properties,
                clock,
                new AuthMetrics(meterRegistry));
    }

    private double refreshResult(String tag) {
        var counter = meterRegistry
                .find("auth.token.refresh.result")
                .tag("result", tag)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void shouldProtectBothIssuedAndClearedAccessCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.setCookie(
                response, new HephaestusJwtIssuer.Token("access-token", UUID.randomUUID(), NOW.plusSeconds(900)));
        service.clearCookie(response);

        assertThat(response.getCookies()).hasSize(2);
        for (Cookie cookie : response.getCookies()) {
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.isHttpOnly()).isTrue();
            assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getDomain()).isNull();
        }
        assertThat(response.getCookies()[0].getMaxAge()).isEqualTo(900);
        assertThat(response.getCookies()[1].getMaxAge()).isZero();
    }

    private static void assertCookieCleared(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie("__Host-HEPHAESTUS_AT");
        assertThat(cookie)
                .as("session-ending path must clear the access cookie")
                .isNotNull();
        assertThat(cookie.getMaxAge()).isZero();
        assertThat(cookie.getValue()).isEmpty();
    }

    private AuthEventData capturedEvent() {
        ArgumentCaptor<AuthEventData> captor = ArgumentCaptor.forClass(AuthEventData.class);
        verify(authEventWriter).write(captor.capture());
        return captor.getValue();
    }

    private static TokenConstraints ctx(@Nullable Instant sessionExpiresAt) {
        return TokenConstraints.session(sessionExpiresAt, AUTH_TIME);
    }

    private void presentingTokenIsActive(UUID jti) {
        when(issuedJwtRepository.findActive(eq(jti), any()))
                .thenReturn(Optional.of(new IssuedJwt(jti, ACCOUNT_ID, SESSION_CEILING)));
    }

    private static HephaestusJwtIssuer.Token freshToken() {
        return new HephaestusJwtIssuer.Token("fresh-token", UUID.randomUUID(), NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void logout_revokesPresentingTokenAndAuditsLogout() {
        UUID jti = UUID.randomUUID();

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.logout(ACCOUNT_ID, jti, null, response);

        verify(issuedJwtRepository).revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.LOGOUT));
        verify(issuedJwtRepository).revokeUnusedSuccessors(eq(jti), any(), eq(IssuedJwt.RevokedReason.LOGOUT));
        assertCookieCleared(response);
        AuthEventData event = capturedEvent();
        assertThat(event.type()).isEqualTo(AuthEvent.EventType.LOGOUT);
        assertThat(event.result()).isEqualTo(AuthEvent.Result.SUCCESS);
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void shouldEndTheWholeClientSessionAndSetNoCookieWhenTheTokenCarriesASid() {
        UUID jti = UUID.randomUUID();
        UUID sid = UUID.randomUUID();

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.logout(ACCOUNT_ID, jti, sid, response);

        verify(clientSessionService).endSession(ACCOUNT_ID, sid, IssuedJwt.RevokedReason.LOGOUT);
        verify(issuedJwtRepository, never()).revoke(any(), any(), any());
        assertThat(response.getCookies()).isEmpty();
    }

    @Test
    void shouldEndTheFamilyWhenARevokedJtiBelongsToAClientSession() {
        UUID oldJti = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        when(issuedJwtRepository.findSessionIdOwnedBy(oldJti, ACCOUNT_ID)).thenReturn(Optional.of(sid));

        service.revokeSession(ACCOUNT_ID, oldJti);

        verify(clientSessionService).endSession(ACCOUNT_ID, sid, IssuedJwt.RevokedReason.SELF_REVOKE);
        verify(issuedJwtRepository, never()).revokeOwned(any(), any(), any(), any());
    }

    @Test
    void revokeSession_endsABrowserTogetherWithItsUnusedSuccessor() {
        UUID jti = UUID.randomUUID();
        when(issuedJwtRepository.revokeOwned(eq(jti), eq(ACCOUNT_ID), any(), eq(IssuedJwt.RevokedReason.SELF_REVOKE)))
                .thenReturn(1);

        service.revokeSession(ACCOUNT_ID, jti);

        verify(issuedJwtRepository).revokeUnusedSuccessors(eq(jti), any(), eq(IssuedJwt.RevokedReason.SELF_REVOKE));
    }

    @Test
    void activeSessions_showsABrowserOnceWhileItsSuccessorIsUnused() {
        UUID held = UUID.randomUUID();
        IssuedJwt unused = new IssuedJwt(UUID.randomUUID(), ACCOUNT_ID, SESSION_CEILING);
        unused.setPredecessorJti(held);
        when(issuedJwtRepository.findActiveByAccountId(eq(ACCOUNT_ID), any()))
                .thenReturn(List.of(new IssuedJwt(held, ACCOUNT_ID, SESSION_CEILING), unused));

        assertThat(service.activeSessions(ACCOUNT_ID))
                .extracting(AuthSessionService.SessionEntry::jti)
                .containsExactly(held);
    }

    @Test
    void shouldKeepTheCallersWholeClientSessionWhenSigningOutEverywhereElse() {
        UUID jti = UUID.randomUUID();
        UUID sid = UUID.randomUUID();

        service.revokeAllExcept(ACCOUNT_ID, jti, sid);
        service.revokeAllExcept(ACCOUNT_ID, jti, null);

        verify(sessionRevocation).revokeAccount(ACCOUNT_ID, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, null, sid);
        verify(sessionRevocation).revokeAccount(ACCOUNT_ID, IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE, jti, null);
    }

    @Test
    void refresh_whenTheSessionCeilingHasPassed_endsTheSession() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        presentingTokenIsActive(jti);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(
                        ACCOUNT_ID,
                        jti,
                        ctx(NOW.minus(Duration.ofSeconds(1))),
                        mock(HttpServletRequest.class),
                        response))
                .isFalse();

        assertThat(refreshResult("noop")).isEqualTo(1.0);
        assertCookieCleared(response);
        verify(issuedJwtRepository).revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.LOGOUT));
        verify(jwtIssuer, never()).issueSuccessor(any(), any(), any(), any());
    }

    @Test
    void refresh_whenASuccessorsFirstUseRetiredThePresentingToken_writesNoCookie() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        when(issuedJwtRepository.findActive(eq(jti), any())).thenReturn(Optional.empty());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isTrue();

        assertThat(refreshResult("noop")).isEqualTo(1.0);
        assertThat(refreshResult("success")).isZero();
        assertThat(response.getCookies()).isEmpty();
        verify(jwtIssuer, never()).issueSuccessor(any(), any(), any(), any());
    }

    @Test
    void refresh_whenAccountNotActive_recordsSuspendedAndDoesNotReMint() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isFalse();

        assertThat(refreshResult("suspended")).isEqualTo(1.0);
        assertCookieCleared(response);
        verify(issuedJwtRepository).revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE));
        verify(issuedJwtRepository).revokeUnusedSuccessors(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE));
        verify(jwtIssuer, never()).issueSuccessor(any(), any(), any(), any());
    }

    @Test
    void refresh_issuesASuccessorAndKeepsThePresentingTokenUntilTheSuccessorIsUsed() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        presentingTokenIsActive(jti);
        when(jwtIssuer.issueSuccessor(any(), any(), eq(jti), any())).thenReturn(freshToken());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isTrue();

        assertThat(refreshResult("success")).isEqualTo(1.0);
        var cookie = response.getCookie("__Host-HEPHAESTUS_AT");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("fresh-token");
        verify(issuedJwtRepository, never()).revoke(any(), any(), any());
        AuthEventData event = capturedEvent();
        assertThat(event.type()).isEqualTo(AuthEvent.EventType.TOKEN_REFRESH);
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(event.details()).isNull();
    }

    @Test
    void refresh_whenTheSuccessorWasNeverUsed_replacesIt() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        presentingTokenIsActive(jti);
        when(issuedJwtRepository.revokeUnusedSuccessors(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(jwtIssuer.issueSuccessor(any(), any(), eq(jti), any())).thenReturn(freshToken());

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isTrue();

        assertThat(response.getCookie("__Host-HEPHAESTUS_AT")).isNotNull();
        AuthEventData event = capturedEvent();
        assertThat(event.type()).isEqualTo(AuthEvent.EventType.TOKEN_REFRESH);
        assertThat(event.result()).isEqualTo(AuthEvent.Result.SUCCESS);
        assertThat(event.details()).isEqualTo("{\"reason\":\"SUCCESSOR_UNUSED_REPLACED\"}");
    }

    @Test
    void refresh_carriesTheOriginalSignInTimeForward() {
        UUID jti = UUID.randomUUID();
        Instant ceiling = NOW.plus(Duration.ofHours(6));
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        presentingTokenIsActive(jti);
        when(jwtIssuer.issueSuccessor(any(), any(), any(), any())).thenReturn(freshToken());

        service.refresh(ACCOUNT_ID, jti, ctx(ceiling), mock(HttpServletRequest.class), new MockHttpServletResponse());

        verify(jwtIssuer)
                .issueSuccessor(
                        any(),
                        eq(TokenConstraints.session(ceiling, AUTH_TIME)),
                        eq(jti),
                        any(HttpServletRequest.class));
    }

    @Test
    void refresh_whenReMintThrows_recordsErrorAndPropagates() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        presentingTokenIsActive(jti);
        when(jwtIssuer.issueSuccessor(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("signing key unavailable"));

        assertThatThrownBy(() -> service.refresh(
                        ACCOUNT_ID,
                        jti,
                        ctx(SESSION_CEILING),
                        mock(HttpServletRequest.class),
                        new MockHttpServletResponse()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(refreshResult("error")).isEqualTo(1.0);
        assertThat(refreshResult("success")).isZero();
        var timer = meterRegistry.find("auth.token.refresh").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1L);
    }
}
