package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSession;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.ClientSessionService;
import de.tum.cit.aet.hephaestus.core.auth.clientsession.InstalledClientKind;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt.RevokedReason;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.metrics.AuthMetrics;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns session rotation, revocation, and access-token cookies. */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Session lifecycle is account-scoped, not workspace-scoped")
public class AuthSessionService {

    private final IssuedJwtRepository issuedJwtRepository;
    private final AccountRepository accountRepository;
    private final HephaestusJwtIssuer jwtIssuer;
    private final AuthEventLogger authEventLogger;
    private final ClientSessionService clientSessionService;
    private final SessionRevocation sessionRevocation;
    private final AuthProperties properties;
    private final Clock clock;
    private final AuthMetrics metrics;

    public AuthSessionService(
            IssuedJwtRepository issuedJwtRepository,
            AccountRepository accountRepository,
            HephaestusJwtIssuer jwtIssuer,
            AuthEventLogger authEventLogger,
            ClientSessionService clientSessionService,
            SessionRevocation sessionRevocation,
            AuthProperties properties,
            Clock clock,
            AuthMetrics metrics) {
        this.issuedJwtRepository = issuedJwtRepository;
        this.accountRepository = accountRepository;
        this.jwtIssuer = jwtIssuer;
        this.authEventLogger = authEventLogger;
        this.clientSessionService = clientSessionService;
        this.sessionRevocation = sessionRevocation;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Ends the presenting session. A browser token is revoked and its cookie cleared; an installed-client
     * token ({@code sessionId} present) ends its whole session.
     */
    @Transactional
    public void logout(Long accountId, UUID jti, @Nullable UUID sessionId, HttpServletResponse response) {
        if (sessionId != null) {
            clientSessionService.endSession(accountId, sessionId, IssuedJwt.RevokedReason.LOGOUT);
        } else {
            issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.LOGOUT);
        }
        authEventLogger
                .event(AuthEvent.EventType.LOGOUT, AuthEvent.Result.SUCCESS)
                .account(accountId)
                .record();
        if (sessionId == null) {
            clearCookie(response);
        }
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
            // The account lock comes before the token row, the order every issuance and revocation uses,
            // so an account-wide revocation either sees the rotated token or this refresh sees it.
            if (!accountRepository.lockForIssuance(accountId)) {
                issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.ROTATE);
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.SUSPENDED);
                clearCookie(response);
                return false;
            }
            // Only the request that revokes this token may replace it. A losing response must not
            // write a cookie, which could overwrite the winning request's new session.
            int revoked = issuedJwtRepository.revoke(jti, clock.instant(), IssuedJwt.RevokedReason.ROTATE);
            if (revoked == 0) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.NOOP);
                return true;
            }
            if (sessionExpiresAt == null || !clock.instant().isBefore(sessionExpiresAt)) {
                metrics.recordRefreshResult(AuthMetrics.RefreshResult.NOOP);
                clearCookie(response);
                return false;
            }
            HephaestusJwtIssuer.Token token =
                    jwtIssuer.issue(accountId, TokenConstraints.session(sessionExpiresAt, context.authTime()), request);
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

    /**
     * One signed-in place in the session list: a browser token, or an installed-client session shown once
     * by its current token with the session's deadline, even while its access token has lapsed.
     *
     * @param sessionId  the installed-client session, or null for a browser token
     * @param clientKind the installed client, or null for a browser token
     */
    public record SessionEntry(
            UUID jti,
            @Nullable Instant issuedAt,
            Instant expiresAt,
            @Nullable String userAgent,
            @Nullable String ip,
            @Nullable UUID sessionId,
            @Nullable InstalledClientKind clientKind) {}

    /** Every signed-in place of an account: active browser tokens and live installed-client sessions. */
    @Transactional(readOnly = true)
    public List<SessionEntry> activeSessions(Long accountId) {
        List<SessionEntry> entries = new ArrayList<>();
        for (IssuedJwt token : issuedJwtRepository.findActiveByAccountId(accountId, clock.instant())) {
            if (token.getSessionId() == null) {
                entries.add(new SessionEntry(
                        token.getJti(),
                        token.getIssuedAt(),
                        token.getExpiresAt(),
                        token.getUserAgent(),
                        token.getIpInet(),
                        null,
                        null));
            }
        }
        Map<UUID, ClientSession> live = clientSessionService.liveSessions(accountId).stream()
                .collect(Collectors.toMap(ClientSession::getId, Function.identity()));
        if (!live.isEmpty()) {
            for (IssuedJwt token : issuedJwtRepository.findCurrentBySessionIds(live.keySet())) {
                ClientSession session = live.get(token.getSessionId());
                if (session != null) {
                    entries.add(new SessionEntry(
                            token.getJti(),
                            token.getIssuedAt(),
                            session.getSessionExpiresAt(),
                            token.getUserAgent(),
                            token.getIpInet(),
                            session.getId(),
                            session.getClientKind()));
                }
            }
        }
        return entries;
    }

    /**
     * Revokes one session owned by {@code accountId}. A token of an installed-client session, current or
     * long rotated away, ends that whole session; a browser token is revoked on its own, with ownership
     * checked in the update.
     */
    @Transactional
    public void revokeSession(Long accountId, UUID jti) {
        UUID sessionId =
                issuedJwtRepository.findSessionIdOwnedBy(jti, accountId).orElse(null);
        if (sessionId != null) {
            clientSessionService.endSession(accountId, sessionId, RevokedReason.SELF_REVOKE);
            return;
        }
        issuedJwtRepository.revokeOwned(jti, accountId, clock.instant(), RevokedReason.SELF_REVOKE);
    }

    /**
     * Sign out everywhere except the presenting session: its browser token, or its whole installed-client
     * session when it presents one ({@code currentSessionId}).
     */
    @Transactional
    public void revokeAllExcept(Long accountId, UUID currentJti, @Nullable UUID currentSessionId) {
        sessionRevocation.revokeAccount(
                accountId,
                RevokedReason.SIGN_OUT_EVERYWHERE,
                currentSessionId == null ? currentJti : null,
                currentSessionId);
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
