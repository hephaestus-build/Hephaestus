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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
     * How long before {@code imp_exp} a rotation already exits. A token minted at the deadline would be
     * born expired, so the operator would be signed out instead of returned to their own session. Keep
     * this at or above the SPA's {@code REFRESH_SKEW_MS} in {@code use-session-keep-alive.ts}, which
     * decides when that rotation happens.
     */
    private static final Duration IMPERSONATION_EXIT_SKEW = Duration.ofSeconds(60);

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
        Long impersonatorId = context.impersonatorId();
        Instant impersonationExpiresAt = context.impersonationExpiresAt();
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
            // An impersonation is only ever as legitimate as the operator behind it. Suspending or
            // demoting an operator revokes their own sessions, but the impersonation token's subject is
            // the target, so it survives that sweep — this is where it ends.
            if (impersonatorId != null && !isActiveInstanceAdmin(impersonatorId)) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUSPENDED);
                clearCookie(response);
                return false;
            }
            HephaestusJwtIssuer.Token token;
            if (impersonatorId == null) {
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(accountId),
                        TokenConstraints.session(sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .record();
            } else if (impersonationExpired(impersonationExpiresAt) || isAppAdmin(account)) {
                // Starting impersonation forbids admin targets; promotion must also end an existing one.
                String exitReason = isAppAdmin(account) ? "TARGET_PROMOTED" : "EXPIRED";
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(impersonatorId),
                        TokenConstraints.session(sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.IMPERSONATION_END, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .actingAccount(impersonatorId)
                        .details("{\"reason\":\"" + exitReason + "\"}")
                        .record();
                metrics.recordImpersonationAutoExit(exitReason.toLowerCase(Locale.ROOT));
            } else {
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(accountId),
                        new TokenConstraints(
                                impersonatorId, impersonationExpiresAt, sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .actingAccount(impersonatorId)
                        .record();
            }
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

    private boolean impersonationExpired(@Nullable Instant impersonationExpiresAt) {
        return impersonationExpiresAt == null
                || !clock.instant().plus(IMPERSONATION_EXIT_SKEW).isBefore(impersonationExpiresAt);
    }

    private static boolean isAppAdmin(Account account) {
        return account.getAppRole() == Account.AppRole.APP_ADMIN;
    }

    private boolean isActiveInstanceAdmin(Long accountId) {
        Account operator = accountRepository.findById(accountId).orElse(null);
        return operator != null && operator.getStatus() == Account.Status.ACTIVE && isAppAdmin(operator);
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
