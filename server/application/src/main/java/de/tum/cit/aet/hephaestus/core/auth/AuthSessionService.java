package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt.RevokedReason;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns session rotation, revocation, and access-token cookies. */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Session lifecycle is account-scoped, not workspace-scoped")
public class AuthSessionService {

    private final JwtPrincipalFactory principalFactory;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AccountRepository accountRepository;
    private final HephaestusJwtIssuer jwtIssuer;
    private final AuthEventLogger authEventLogger;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuthMetrics metrics;

    public AuthSessionService(
            JwtPrincipalFactory principalFactory,
            IssuedJwtRepository issuedJwtRepository,
            AccountRepository accountRepository,
            HephaestusJwtIssuer jwtIssuer,
            AuthEventLogger authEventLogger,
            AuthProperties properties,
            Clock clock,
            AuthMetrics metrics) {
        this.principalFactory = principalFactory;
        this.issuedJwtRepository = issuedJwtRepository;
        this.accountRepository = accountRepository;
        this.jwtIssuer = jwtIssuer;
        this.authEventLogger = authEventLogger;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** Revoke the presenting token and clear the cookie. */
    @Transactional
    public void logout(Long accountId, UUID jti, HttpServletResponse response) {
        issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.LOGOUT);
        authEventLogger
                .event(AuthEvent.EventType.LOGOUT, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .record();
        clearCookie(response);
    }

    /**
     * Rotates the session, returning false when the presenting session must end. A lost rotation race
     * leaves the response untouched because another request may already have renewed the session.
     */
    @Transactional
    public boolean refresh(
            Long accountId,
            UUID jti,
            TokenConstraints context,
            HttpServletRequest request,
            HttpServletResponse response) {
        Instant sessionExpiresAt = context.sessionExpiresAt();
        Timer.Sample sample = metrics.startRefreshTimer();
        try {
            // Only the request that revokes this token may replace it. A losing response must not
            // write a cookie, which could overwrite the winning request's new session.
            int revoked = issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.ROTATE);
            if (revoked == 0) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.NOOP);
                return true;
            }
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null || account.getStatus() != Account.Status.ACTIVE) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUSPENDED);
                clearCookie(response);
                return false;
            }
            if (sessionExpiresAt == null || !clock.instant().isBefore(sessionExpiresAt)) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.NOOP);
                clearCookie(response);
                return false;
            }
            HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                    principalFactory.forAccountId(accountId),
                    TokenConstraints.session(sessionExpiresAt, context.authTime()),
                    request);
            authEventLogger
                    .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                    .account(accountId)
                    .record();
            setCookie(response, token);
            metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUCCESS);
            return true;
        } catch (RuntimeException e) {
            metrics.recordRefreshResult(AuthMetrics.RefreshResult.ERROR);
            throw e;
        } finally {
            metrics.stopRefreshTimer(sample);
        }
    }

    /** Write a freshly-minted token to the {@code __Host-} access cookie. */
    public void setCookie(HttpServletResponse response, HephaestusJwtIssuer.Token token) {
        long maxAge = token.expiresAt().getEpochSecond() - clock.instant().getEpochSecond();
        Cookie cookie = new Cookie(properties.cookieName(), token.value());
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge((int) Math.max(0, maxAge));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** Active (non-revoked, non-expired) sessions for an account. */
    public List<IssuedJwt> activeSessions(Long accountId) {
        return issuedJwtRepository.findActiveByAccountId(accountId, clock.instant());
    }

    /** Revokes only sessions owned by {@code accountId}; ownership is checked in the update. */
    @Transactional
    public void revokeSession(Long accountId, UUID jti) {
        issuedJwtRepository.revokeOwned(jti, accountId, clock.instant(), RevokedReason.SELF_REVOKE);
    }

    /** Sign out everywhere except the presenting session. */
    @Transactional
    public void revokeAllExcept(Long accountId, UUID currentJti) {
        issuedJwtRepository.revokeAllForAccountExcept(
                accountId, currentJti, clock.instant(), RevokedReason.SIGN_OUT_EVERYWHERE);
    }

    public void clearCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(properties.cookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setSecure(properties.cookieSecure());
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }
}
