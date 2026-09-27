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
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private static void assertCookieCleared(MockHttpServletResponse response) {
        jakarta.servlet.http.Cookie cookie = response.getCookie("__Host-HEPHAESTUS_AT");
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

    @Test
    void logout_revokesPresentingTokenAndAuditsLogout() {
        UUID jti = UUID.randomUUID();

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.logout(ACCOUNT_ID, jti, null, response);

        verify(issuedJwtRepository).revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.LOGOUT));
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
        when(issuedJwtRepository.findSessionIdOwnedBy(oldJti, ACCOUNT_ID)).thenReturn(java.util.Optional.of(sid));

        service.revokeSession(ACCOUNT_ID, oldJti);

        verify(clientSessionService).endSession(ACCOUNT_ID, sid, IssuedJwt.RevokedReason.SELF_REVOKE);
        verify(issuedJwtRepository, never()).revokeOwned(any(), any(), any(), any());
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
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);

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
        verify(jwtIssuer, never()).issue(any(), any(), any());
    }

    @Test
    void refresh_whenConditionalRevokeAffectsZeroRows_recordsNoopAndDoesNotReMint() {
        UUID jti = UUID.randomUUID();
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(0);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isTrue();

        assertThat(refreshResult("noop")).isEqualTo(1.0);
        assertThat(refreshResult("success")).isZero();
        assertThat(response.getCookies()).isEmpty();
        verify(jwtIssuer, never()).issue(any(), any(), any());
    }

    @Test
    void refresh_whenAccountNotActive_recordsSuspendedAndDoesNotReMint() {
        UUID jti = UUID.randomUUID();
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isFalse();

        assertThat(refreshResult("suspended")).isEqualTo(1.0);
        assertCookieCleared(response);
        verify(jwtIssuer, never()).issue(any(), any(), any());
    }

    @Test
    void refresh_whenAccountMissing_recordsSuspended() {
        UUID jti = UUID.randomUUID();
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(false);

        assertThat(service.refresh(
                        ACCOUNT_ID,
                        jti,
                        ctx(SESSION_CEILING),
                        mock(HttpServletRequest.class),
                        new MockHttpServletResponse()))
                .isFalse();

        assertThat(refreshResult("suspended")).isEqualTo(1.0);
    }

    @Test
    void refresh_whenReMintSucceeds_recordsSuccessAndSetsCookie() {
        UUID jti = UUID.randomUUID();
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        HephaestusJwtIssuer.Token token =
                new HephaestusJwtIssuer.Token("fresh-token", UUID.randomUUID(), NOW.plus(Duration.ofMinutes(15)));
        when(jwtIssuer.issue(any(), any(), any())).thenReturn(token);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(service.refresh(ACCOUNT_ID, jti, ctx(SESSION_CEILING), mock(HttpServletRequest.class), response))
                .isTrue();

        assertThat(refreshResult("success")).isEqualTo(1.0);
        var cookie = response.getCookie("__Host-HEPHAESTUS_AT");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isEqualTo("fresh-token");
        AuthEventData event = capturedEvent();
        assertThat(event.type()).isEqualTo(AuthEvent.EventType.TOKEN_REFRESH);
        assertThat(event.accountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    void refresh_carriesTheOriginalSignInTimeForward() {
        UUID jti = UUID.randomUUID();
        Instant ceiling = NOW.plus(Duration.ofHours(6));
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        when(jwtIssuer.issue(any(), any(), any()))
                .thenReturn(
                        new HephaestusJwtIssuer.Token("fresh", UUID.randomUUID(), NOW.plus(Duration.ofMinutes(15))));

        service.refresh(ACCOUNT_ID, jti, ctx(ceiling), mock(HttpServletRequest.class), new MockHttpServletResponse());

        verify(jwtIssuer).issue(any(), eq(TokenConstraints.session(ceiling, AUTH_TIME)), any(HttpServletRequest.class));
    }

    @Test
    void refresh_whenReMintThrows_recordsErrorAndPropagates() {
        UUID jti = UUID.randomUUID();
        when(issuedJwtRepository.revoke(eq(jti), any(), eq(IssuedJwt.RevokedReason.ROTATE)))
                .thenReturn(1);
        when(accountRepository.lockForIssuance(ACCOUNT_ID)).thenReturn(true);
        when(jwtIssuer.issue(any(), any(), any())).thenThrow(new IllegalStateException("signing key unavailable"));

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
