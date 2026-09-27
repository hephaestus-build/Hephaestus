package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and ends native app sessions.
 *
 * <p>Every access token a native session holds is an ordinary {@code issued_jwt}-backed token, so the
 * decoder, the consent gate and every existing revocation path apply to it unchanged. What this service
 * adds is the refresh secret that outlives one access token, bounded by the same absolute
 * {@code session_exp} a browser session gets at sign-in and never extended by a rotation.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Native app sessions are account-scoped, not workspace-scoped")
public class NativeSessionService {

    private static final Logger log = LoggerFactory.getLogger(NativeSessionService.class);

    /** A handoff code only has to survive the redirect back into the app and one request. */
    static final Duration HANDOFF_TTL = Duration.ofSeconds(60);

    private final NativeSessionRepository sessionRepository;
    private final NativeSessionTokenRepository tokenRepository;
    private final NativeSignInHandoffRepository handoffRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AccountRepository accountRepository;
    private final HephaestusJwtIssuer jwtIssuer;
    private final JwtPrincipalFactory principalFactory;
    private final AuthEventLogger authEventLogger;
    private final Clock clock;

    public NativeSessionService(
            NativeSessionRepository sessionRepository,
            NativeSessionTokenRepository tokenRepository,
            NativeSignInHandoffRepository handoffRepository,
            IssuedJwtRepository issuedJwtRepository,
            AccountRepository accountRepository,
            HephaestusJwtIssuer jwtIssuer,
            JwtPrincipalFactory principalFactory,
            AuthEventLogger authEventLogger,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.tokenRepository = tokenRepository;
        this.handoffRepository = handoffRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.accountRepository = accountRepository;
        this.jwtIssuer = jwtIssuer;
        this.principalFactory = principalFactory;
        this.authEventLogger = authEventLogger;
        this.clock = clock;
    }

    /** The tokens a native session holds after a sign-in or a rotation; they travel only in a response body. */
    public record NativeTokens(
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant sessionExpiresAt,
            UUID nativeSessionId) {}

    /**
     * Stores a single-use handoff for a sign-in that just completed in the browser and returns the code
     * the redirect carries back to the app. The session deadline and {@code auth_time} are fixed here,
     * at the sign-in, exactly as a browser sign-in fixes them.
     */
    @Transactional
    public String createHandoff(Long accountId, String codeChallenge, Instant sessionExpiresAt, Instant authTime) {
        String code = Pkce.newSecret();
        handoffRepository.save(new NativeSignInHandoff(
                Pkce.hash(code),
                accountId,
                codeChallenge,
                sessionExpiresAt,
                authTime,
                clock.instant().plus(HANDOFF_TTL)));
        return code;
    }

    /**
     * Redeems a handoff code. The code is consumed before the verifier is checked, so a wrong verifier
     * burns it rather than leaving it open to guessing.
     */
    @Transactional
    public Optional<NativeTokens> exchange(String code, String codeVerifier, @Nullable HttpServletRequest request) {
        Instant now = clock.instant();
        String codeHash = Pkce.hash(code);
        if (handoffRepository.consume(codeHash, now) != 1) {
            return Optional.empty();
        }
        NativeSignInHandoff handoff = handoffRepository.findById(codeHash).orElseThrow();
        if (!Pkce.verifies(codeVerifier, handoff.getCodeChallenge())) {
            log.warn("auth.native: handoff code redeemed with a verifier that does not match its challenge");
            return Optional.empty();
        }
        if (!lockActiveAccount(handoff.getAccountId()) || !now.isBefore(handoff.getSessionExpiresAt())) {
            return Optional.empty();
        }
        UUID sessionId = UUID.randomUUID();
        String refreshToken = Pkce.newSecret();
        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccountId(handoff.getAccountId()),
                TokenConstraints.nativeSession(sessionId, handoff.getSessionExpiresAt(), handoff.getAuthTime()),
                request);
        sessionRepository.save(new NativeSession(
                sessionId, handoff.getAccountId(), token.jti(), handoff.getSessionExpiresAt(), handoff.getAuthTime()));
        tokenRepository.save(new NativeSessionToken(token.jti(), sessionId, Pkce.hash(refreshToken)));
        return Optional.of(new NativeTokens(
                token.value(), token.expiresAt(), refreshToken, handoff.getSessionExpiresAt(), sessionId));
    }

    /**
     * Rotates a native session: revokes the access token it backs and issues a new token and secret, both
     * capped at the unchanged session deadline. Empty means the session has ended and the app must sign in.
     *
     * <p>All native credential changes acquire locks in account → session → issued-token order. The
     * account lock also serializes rotations with account-wide revocation, whose bulk token update
     * would otherwise miss a token inserted by a concurrent refresh. Reusing any replaced secret ends
     * the session; a lost response requires signing in again rather than silently accepting replay.
     */
    @Transactional
    public Optional<NativeTokens> refresh(String refreshToken, @Nullable HttpServletRequest request) {
        String presentedHash = Pkce.hash(refreshToken);
        NativeSession session = lockSessionForSecret(presentedHash);
        if (session == null || session.getRevokedAt() != null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!tokenRepository.existsByJtiAndRefreshTokenHash(session.getCurrentJti(), presentedHash)) {
            end(session, NativeSession.RevokedReason.REFRESH_REUSE, IssuedJwt.RevokedReason.REFRESH_REUSE, now);
            authEventLogger
                    .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.FAILURE)
                    .account(session.getAccountId())
                    .failureReason("native_refresh_reuse")
                    .record();
            log.warn("auth.native: replaced refresh secret reused; session ended");
            return Optional.empty();
        }
        if (!now.isBefore(session.getSessionExpiresAt()) || !isActive(session.getAccountId())) {
            end(session, NativeSession.RevokedReason.SESSION_ENDED, IssuedJwt.RevokedReason.LOGOUT, now);
            return Optional.empty();
        }
        // The backing token's revocation is the session's revocation, however it happened. The
        // conditional update is what serialises this against every other revocation path.
        if (issuedJwtRepository.revoke(session.getCurrentJti(), now, IssuedJwt.RevokedReason.ROTATE) != 1) {
            end(session, NativeSession.RevokedReason.SESSION_ENDED, IssuedJwt.RevokedReason.LOGOUT, now);
            return Optional.empty();
        }
        String nextRefreshToken = Pkce.newSecret();
        HephaestusJwtIssuer.Token token = jwtIssuer.issue(
                principalFactory.forAccountId(session.getAccountId()),
                TokenConstraints.nativeSession(session.getId(), session.getSessionExpiresAt(), session.getAuthTime()),
                request);
        session.setCurrentJti(token.jti());
        tokenRepository.save(new NativeSessionToken(token.jti(), session.getId(), Pkce.hash(nextRefreshToken)));
        sessionRepository.save(session);
        authEventLogger
                .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                .account(session.getAccountId())
                .record();
        return Optional.of(new NativeTokens(
                token.value(), token.expiresAt(), nextRefreshToken, session.getSessionExpiresAt(), session.getId()));
    }

    /**
     * Signs a native session out with its refresh secret, current or rotated away: the app may have
     * queued it before a refresh it never saw the answer to. Unknown secrets are ignored so the call
     * reveals nothing.
     */
    @Transactional
    public void logout(String refreshToken) {
        NativeSession session = lockSessionForSecret(Pkce.hash(refreshToken));
        if (session == null || session.getRevokedAt() != null) {
            return;
        }
        end(session, NativeSession.RevokedReason.LOGOUT, IssuedJwt.RevokedReason.LOGOUT, clock.instant());
        authEventLogger
                .event(AuthEvent.EventType.LOGOUT, AuthEvent.Result.SUCCESS)
                .account(session.getAccountId())
                .record();
    }

    /**
     * Ends the native sessions a revoked access token belonged to. The session list shows a native
     * session by the token it backed when the list was read; a rotation in between moves the session to
     * a new token, which this maps back so the revoke still lands.
     */
    @Transactional
    public void endSessionsBackedBy(Long accountId, UUID jti, IssuedJwt.RevokedReason reason) {
        lockAccount(accountId);
        Instant now = clock.instant();
        for (NativeSession session : sessionRepository.findLiveByAccountAndJti(accountId, jti)) {
            end(session, NativeSession.RevokedReason.SESSION_ENDED, reason, now);
        }
    }

    /** Account-wide revocation closes native authority before the caller sweeps browser tokens. */
    @Transactional
    public int endAll(Long accountId, IssuedJwt.RevokedReason reason) {
        lockAccount(accountId);
        int revoked = 0;
        for (NativeSession session : sessionRepository.lockLiveByAccountId(accountId)) {
            revoked += end(session, NativeSession.RevokedReason.SESSION_ENDED, reason, clock.instant());
        }
        return revoked;
    }

    /** Preserve the presenting native session even if its access token rotated after authentication. */
    @Transactional
    public UUID endAllExcept(Long accountId, UUID keepJti) {
        lockAccount(accountId);
        UUID keepSession = tokenRepository
                .findById(keepJti)
                .map(NativeSessionToken::getSessionId)
                .orElse(null);
        UUID retainedJti = keepJti;
        for (NativeSession session : sessionRepository.lockLiveByAccountId(accountId)) {
            if (session.getId().equals(keepSession)) {
                retainedJti = session.getCurrentJti();
            } else {
                end(
                        session,
                        NativeSession.RevokedReason.SESSION_ENDED,
                        IssuedJwt.RevokedReason.SIGN_OUT_EVERYWHERE,
                        clock.instant());
            }
        }
        return retainedJti;
    }

    private @Nullable NativeSession lockSessionForSecret(String hash) {
        Long accountId = tokenRepository.findAccountForSecret(hash).orElse(null);
        if (accountId == null || accountRepository.findByIdForUpdate(accountId).isEmpty()) {
            return null;
        }
        return sessionRepository.lockBySecretHash(hash).orElse(null);
    }

    private void lockAccount(Long accountId) {
        accountRepository.findByIdForUpdate(accountId);
    }

    private boolean lockActiveAccount(Long accountId) {
        return accountRepository
                .findByIdForUpdate(accountId)
                .map(account -> account.getStatus() == Account.Status.ACTIVE)
                .orElse(false);
    }

    /** Live native sessions, including those whose access token expired while the app sat idle. */
    public List<NativeSession> liveSessions(Long accountId) {
        return sessionRepository.findLiveByAccountId(accountId, clock.instant());
    }

    private int end(
            NativeSession session,
            NativeSession.RevokedReason reason,
            IssuedJwt.RevokedReason tokenReason,
            Instant now) {
        session.setRevokedAt(now);
        session.setRevokedReason(reason);
        sessionRepository.save(session);
        return issuedJwtRepository.revoke(session.getCurrentJti(), now, tokenReason);
    }

    private boolean isActive(Long accountId) {
        return accountRepository
                .findById(accountId)
                .map(account -> account.getStatus() == Account.Status.ACTIVE)
                .orElse(false);
    }
}
