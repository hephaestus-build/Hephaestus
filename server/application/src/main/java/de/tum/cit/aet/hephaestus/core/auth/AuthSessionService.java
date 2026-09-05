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
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the access-token cookie lifecycle: logout, refresh, and the low-level
 * set/clear of the {@code __Host-} cookie. Keeps the cookie/JWT mechanics in one place so
 * {@code AuthLifecycleController} stays thin (≤5 deps).
 */
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

    /** Rotate: revoke the presenting token, mint a fresh one (preserving impersonation), set cookie. */
    @Transactional
    public void refresh(
            Long accountId,
            UUID jti,
            TokenConstraints context,
            HttpServletRequest request,
            HttpServletResponse response) {
        Long impersonatorId = context.impersonatorId();
        Instant impersonationExpiresAt = context.impersonationExpiresAt();
        Instant sessionExpiresAt = context.sessionExpiresAt();
        // Time the full rotation critical section (revoke + status-gate + re-mint), including the
        // early-return races — those are the cheap paths and keep the timer's count == refresh calls.
        Timer.Sample sample = metrics.startRefreshTimer();
        try {
            // Atomically revoke the presenting token. The conditional UPDATE (revokedAt IS NULL) affects
            // 0 rows when a concurrent refresh/logout already rotated this jti — in that race we must NOT
            // mint a fresh token (it would resurrect a session the other request meant to end). No-op and
            // clear the cookie so the client re-authenticates.
            int revoked = issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.ROTATE);
            if (revoked == 0) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.NOOP);
                clearCookie(response);
                return;
            }
            // Account-status gate (ADR 0017). A SUSPENDED / DELETING / DELETED account must not be able to
            // rotate its session into a fresh JWT — that would keep a suspended/deleting principal alive
            // indefinitely. The presenting token is already revoked above; we simply do NOT re-mint, clear
            // the cookie, and end the session. (forAccountId would also reject as defense-in-depth.)
            Account account = accountRepository.findById(accountId).orElse(null);
            if (account == null || account.getStatus() != Account.Status.ACTIVE) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUSPENDED);
                clearCookie(response);
                return;
            }
            HephaestusJwtIssuer.Token token;
            if (impersonatorId == null) {
                // Ordinary (non-impersonation) rotation — carry the absolute session ceiling forward so
                // the rotated token is re-capped at it (OWASP absolute timeout; impersonation uses imp_exp).
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(accountId),
                        TokenConstraints.session(sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .record();
            } else if (impersonationExpired(impersonationExpiresAt)) {
                // Impersonation time-box reached: auto-exit to the operator (mint an operator token
                // with NO act claim) rather than renewing the impersonation forever via silent refresh.
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(impersonatorId),
                        TokenConstraints.session(sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.IMPERSONATION_END, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .actingAccount(impersonatorId)
                        .details("{\"reason\":\"EXPIRED\"}")
                        .record();
            } else {
                // Impersonation rotation: re-cap the new token at the same imp_exp ceiling.
                token = jwtIssuer.issue(
                        principalFactory.forAccountId(accountId),
                        new TokenConstraints(
                                impersonatorId, impersonationExpiresAt, sessionExpiresAt, context.authTime()),
                        request);
                authEventLogger
                        .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                        .account(accountId)
                        .record();
            }
            setCookie(response, token);
            metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUCCESS);
        } catch (RuntimeException e) {
            // The presenting token is already revoked at this point; a re-mint / cookie failure ends the
            // session. Record it so sum(result) == refresh count and an error spike is alertable (the
            // transaction still rolls back and the exception propagates to the controller advice).
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

    /**
     * Whether an impersonation session has hit its absolute time-box. A missing ceiling is treated as
     * expired (fail-safe: a legacy impersonation token without {@code imp_exp} auto-exits on its next
     * refresh rather than living forever).
     */
    private boolean impersonationExpired(@Nullable Instant impersonationExpiresAt) {
        return impersonationExpiresAt == null || !clock.instant().isBefore(impersonationExpiresAt);
    }

    /** Active (non-revoked, non-expired) sessions for an account. */
    public List<IssuedJwt> activeSessions(Long accountId) {
        return issuedJwtRepository.findActiveByAccountId(accountId, clock.instant());
    }

    /**
     * Revoke a single session, only if it belongs to {@code accountId}. Atomic ownership-scoped UPDATE
     * (no findById-then-revoke TOCTOU): a row not owned by {@code accountId}, missing, or already
     * revoked simply affects 0 rows. The {@code accountId} predicate is the access control.
     */
    @Transactional
    public void revokeSession(Long accountId, UUID jti) {
        issuedJwtRepository.revokeOwned(jti, accountId, clock.instant(), RevokedReason.SELF_REVOKE);
    }

    /** Sign out everywhere except the presenting session. */
    @Transactional
    public void revokeAllExcept(Long accountId, UUID currentJti) {
        // The negative-cache decoder re-checks every ACTIVE token against the DB, so the bulk revoke
        // takes effect on all pods within DB visibility lag — no per-jti cache eviction needed.
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
