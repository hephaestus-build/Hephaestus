package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import de.tum.cit.aet.hephaestus.core.auth.mcp.McpClientGrant;
import de.tum.cit.aet.hephaestus.core.auth.mcp.McpClientGrantRepository;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwt;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and ends installed-client sessions.
 *
 * <p>Lock order, everywhere: the account row ({@code FOR SHARE} here), then the {@code client_session}
 * row ({@code FOR UPDATE}), then {@code issued_jwt} rows. Account-wide revocation takes the account row
 * {@code FOR UPDATE} first ({@code SessionRevocation}), so it and every method here serialize without a
 * second authority the decoder would have to consult.
 *
 * <p>Methods that refuse after changing state (a burned code, a session ended for reuse) return empty
 * rather than throw, so the change commits with the refusal.
 */
@ConditionalOnServerRole
@Service
@WorkspaceAgnostic("Installed-client sessions are account-scoped, not workspace-scoped")
public class ClientSessionService {

    private static final Logger log = LoggerFactory.getLogger(ClientSessionService.class);

    /** A handoff code only has to survive the redirect back into the client and one request. */
    static final Duration HANDOFF_TTL = Duration.ofSeconds(60);

    private final ClientSessionRepository sessionRepository;
    private final ClientSignInHandoffRepository handoffRepository;
    private final IssuedJwtRepository issuedJwtRepository;
    private final AccountRepository accountRepository;
    private final InstalledClientRegistry registry;
    private final HephaestusJwtIssuer jwtIssuer;
    private final AuthEventLogger authEventLogger;
    private final McpClientGrantRepository grantRepository;
    private final Clock clock;

    public ClientSessionService(
            ClientSessionRepository sessionRepository,
            ClientSignInHandoffRepository handoffRepository,
            IssuedJwtRepository issuedJwtRepository,
            AccountRepository accountRepository,
            InstalledClientRegistry registry,
            HephaestusJwtIssuer jwtIssuer,
            AuthEventLogger authEventLogger,
            McpClientGrantRepository grantRepository,
            Clock clock) {
        this.sessionRepository = sessionRepository;
        this.handoffRepository = handoffRepository;
        this.issuedJwtRepository = issuedJwtRepository;
        this.accountRepository = accountRepository;
        this.registry = registry;
        this.jwtIssuer = jwtIssuer;
        this.authEventLogger = authEventLogger;
        this.grantRepository = grantRepository;
        this.clock = clock;
    }

    /** The tokens a client holds after a sign-in or a rotation; they travel only in a response body. */
    public record ClientTokens(
            String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant sessionExpiresAt) {}

    /**
     * Stores a single-use handoff for a sign-in that just completed and returns the code the callback
     * carries. The session deadline and {@code auth_time} are fixed here, at the sign-in, exactly as a
     * browser sign-in fixes them. Empty when the account may not sign in.
     */
    @Transactional
    public Optional<String> createHandoff(
            Long accountId, InstalledClient client, String codeChallenge, Instant sessionExpiresAt, Instant authTime) {
        return createHandoff(accountId, client, codeChallenge, sessionExpiresAt, authTime, null);
    }

    /**
     * Stores the handoff of a sign-in or of an approved MCP client grant. The caller holds the account's
     * issuance lock when it approves a grant, so an account-wide revocation burns the code or runs after it.
     */
    @Transactional
    public Optional<String> createHandoff(
            Long accountId,
            InstalledClient client,
            String codeChallenge,
            Instant sessionExpiresAt,
            Instant authTime,
            @Nullable UUID mcpClientGrantId) {
        // Joins the issuance serialization: an account-wide revocation that holds the account lock
        // burns every handoff it can see, so none may be inserted while it runs.
        if (!accountRepository.lockForIssuance(accountId)) {
            return Optional.empty();
        }
        String code = Pkce.newSecret();
        handoffRepository.save(new ClientSignInHandoff(
                Pkce.hash(code),
                accountId,
                client,
                codeChallenge,
                sessionExpiresAt,
                authTime,
                clock.instant().plus(HANDOFF_TTL),
                mcpClientGrantId));
        return Optional.of(code);
    }

    /**
     * Redeems a handoff code for a new session. The code is consumed before anything about the request
     * is checked, so a wrong verifier, client or callback burns it rather than leaving it open to guessing.
     */
    @Transactional
    public Optional<ClientTokens> exchange(
            String clientId,
            String redirectUri,
            String code,
            String codeVerifier,
            @Nullable HttpServletRequest request) {
        Optional<InstalledClient> client = registry.find(clientId, redirectUri);
        String codeHash = Pkce.hash(code);
        Long accountId = handoffRepository.findAccountId(codeHash).orElse(null);
        if (accountId == null) {
            return Optional.empty();
        }
        boolean active = accountRepository.lockForIssuance(accountId);
        Instant now = clock.instant();
        if (handoffRepository.consume(codeHash, now) != 1) {
            return Optional.empty();
        }
        ClientSignInHandoff handoff = handoffRepository.findById(codeHash).orElseThrow();
        if (!Pkce.verifies(codeVerifier, handoff.getCodeChallenge())) {
            log.warn("auth.client: handoff code redeemed with a verifier that does not match its challenge");
            return Optional.empty();
        }
        // A first-party client is checked against the registry; an MCP client's code names its grant, whose
        // client and redirect were checked against its metadata document when the code was approved.
        boolean mcp = handoff.getClientKind() == InstalledClientKind.MCP_CLIENT;
        if ((!mcp && client.isEmpty())
                || !handoff.getClientId().equals(clientId)
                || !handoff.getRedirectUri().equals(redirectUri)) {
            log.warn("auth.client: handoff code redeemed by a client or callback it was not issued to");
            return Optional.empty();
        }
        if (!active || !now.isBefore(handoff.getSessionExpiresAt())) {
            return Optional.empty();
        }
        McpClientGrant grant = null;
        if (mcp) {
            UUID grantId = handoff.getMcpClientGrantId();
            grant = grantId == null ? null : grantRepository.lockById(grantId).orElse(null);
            // A console revoke that won the grant lock leaves it REVOKED; the exchange never reactivates it.
            if (grant == null || grant.getStatus() != McpClientGrant.Status.APPROVED) {
                return Optional.empty();
            }
        }
        ClientSession session = sessionRepository.saveAndFlush(new ClientSession(
                UUID.randomUUID(),
                accountId,
                handoff.getClientKind(),
                handoff.getClientId(),
                handoff.getSessionExpiresAt(),
                handoff.getAuthTime()));
        if (grant != null) {
            grant.activate(session.getId());
        }
        return Optional.of(issue(session, grant, request));
    }

    /**
     * Rotates a session: the presented secret's token is revoked and a new token and secret are issued,
     * both capped at the unchanged session deadline. Empty means the session has ended and the client
     * must sign in again.
     *
     * <p>Rotation is strict. A secret that belongs to the session but was already rotated away means two
     * holders have the family; the whole session ends. A client whose refresh response was lost signs in
     * again.
     */
    @Transactional
    public Optional<ClientTokens> refresh(String refreshToken, @Nullable HttpServletRequest request) {
        return refresh(refreshToken, null, request);
    }

    /**
     * Rotates a session at its own door: a first-party session ({@code mcpClientId} null) at
     * {@code /auth/client/refresh}, an MCP client session at the MCP token endpoint, presenting its client id
     * (OAuth 2.1 § 4.3.1). A session presented at the other door is refused without a change.
     */
    @Transactional
    public Optional<ClientTokens> refresh(
            String refreshToken, @Nullable String mcpClientId, @Nullable HttpServletRequest request) {
        String presentedHash = Pkce.hash(refreshToken);
        ClientSession session = lockSessionOf(presentedHash).orElse(null);
        if (session == null
                || session.getRevokedAt() != null
                || !accountRepository.lockForIssuance(session.getAccountId())) {
            return Optional.empty();
        }
        boolean mcpSession = session.getClientKind() == InstalledClientKind.MCP_CLIENT;
        if (mcpSession != (mcpClientId != null)
                || (mcpClientId != null && !mcpClientId.equals(session.getClientId()))) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!now.isBefore(session.getSessionExpiresAt())) {
            end(session.getId(), IssuedJwt.RevokedReason.LOGOUT, now);
            return Optional.empty();
        }
        if (issuedJwtRepository.rotate(presentedHash, session.getId(), now) != 1) {
            end(session.getId(), IssuedJwt.RevokedReason.REFRESH_REUSE, now);
            authEventLogger
                    .event(AuthEvent.EventType.JWT_REVOKED, AuthEvent.Result.FAILURE)
                    .account(session.getAccountId())
                    .failureReason("client_refresh_reuse")
                    .record();
            log.warn("auth.client: rotated refresh secret presented again; session ended");
            return Optional.empty();
        }
        McpClientGrant grant = null;
        if (mcpSession) {
            grant = grantRepository.findActiveBySession(session.getId()).orElse(null);
            if (grant == null) {
                end(session.getId(), IssuedJwt.RevokedReason.LOGOUT, now);
                return Optional.empty();
            }
        }
        ClientTokens tokens = issue(session, grant, request);
        authEventLogger
                .event(AuthEvent.EventType.TOKEN_REFRESH, AuthEvent.Result.SUCCESS)
                .account(session.getAccountId())
                .record();
        return Optional.of(tokens);
    }

    /**
     * Ends the session a refresh secret belongs to, current or rotated away: a client may have queued its
     * sign-out before a refresh whose answer it never saw. Unknown secrets are ignored so the call
     * reveals nothing.
     */
    @Transactional
    public void logout(String refreshToken) {
        lockSessionOf(Pkce.hash(refreshToken)).ifPresent(session -> {
            if (end(session.getId(), IssuedJwt.RevokedReason.LOGOUT, clock.instant())) {
                authEventLogger
                        .event(AuthEvent.EventType.LOGOUT, AuthEvent.Result.SUCCESS)
                        .account(session.getAccountId())
                        .record();
            }
        });
    }

    /**
     * Ends one session of {@code accountId}: signing out with a client-session access token, or revoking
     * any token of its family from the session list.
     *
     * @return whether a live session was ended
     */
    @Transactional
    public boolean endSession(Long accountId, UUID sessionId, IssuedJwt.RevokedReason reason) {
        accountRepository.lockStatusForShare(accountId);
        ClientSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (session == null || !session.getAccountId().equals(accountId)) {
            return false;
        }
        return end(sessionId, reason, clock.instant());
    }

    /**
     * Revokes an MCP client grant from the workspace console, in the order account, session, grant. The
     * session link is read again under the grant lock: an exchange that linked a session meanwhile is
     * seen, and its session is ended too.
     *
     * @return whether the grant was open
     */
    @Transactional
    public boolean revokeMcpGrant(Long accountId, UUID grantId) {
        accountRepository.lockStatusForShare(accountId);
        Instant now = clock.instant();
        for (int attempt = 0; attempt < 2; attempt++) {
            UUID sessionId = grantRepository.findSessionId(grantId).orElse(null);
            if (sessionId != null) {
                sessionRepository.lockById(sessionId);
            }
            if (grantRepository.lockById(grantId).isEmpty()) {
                return false;
            }
            UUID linked = grantRepository.findSessionId(grantId).orElse(null);
            if (!Objects.equals(linked, sessionId)) {
                continue;
            }
            handoffRepository.consumeForGrant(grantId, now);
            boolean open = grantRepository.revokeIfOpen(grantId, now) == 1;
            if (sessionId != null) {
                end(sessionId, IssuedJwt.RevokedReason.ADMIN_REVOKE, now);
            }
            return open;
        }
        throw new IllegalStateException("the grant's session link changed twice during one revoke");
    }

    /** Sessions that can still refresh, including those whose access token lapsed while the client sat idle. */
    @Transactional(readOnly = true)
    public List<ClientSession> liveSessions(Long accountId) {
        return sessionRepository.findLiveByAccountId(accountId, clock.instant());
    }

    /**
     * Locks the session a refresh secret was issued to, taking the account lock first. The ids are read
     * as scalars so the locked read is the first time this transaction loads the session row.
     */
    private Optional<ClientSession> lockSessionOf(String refreshTokenHash) {
        UUID sessionId = issuedJwtRepository
                .findSessionIdByRefreshTokenHash(refreshTokenHash)
                .orElse(null);
        if (sessionId == null) {
            return Optional.empty();
        }
        Long accountId = sessionRepository.findAccountId(sessionId).orElse(null);
        if (accountId == null) {
            return Optional.empty();
        }
        accountRepository.lockStatusForShare(accountId);
        return sessionRepository.lockById(sessionId);
    }

    /** The session kind shapes the token: an MCP client session's token names only its grant's resource. */
    private ClientTokens issue(
            ClientSession session, @Nullable McpClientGrant grant, @Nullable HttpServletRequest request) {
        String refreshToken = Pkce.newSecret();
        TokenConstraints constraints = grant == null
                ? TokenConstraints.clientSession(session.getId(), session.getSessionExpiresAt(), session.getAuthTime())
                : TokenConstraints.mcpClientSession(
                        session.getId(),
                        session.getSessionExpiresAt(),
                        session.getAuthTime(),
                        new TokenConstraints.McpAudience(grant.getResource(), grant.getScopes()));
        HephaestusJwtIssuer.Token token = jwtIssuer.issueForClientSession(
                session.getAccountId(), constraints, Pkce.hash(refreshToken), request);
        return new ClientTokens(token.value(), token.expiresAt(), refreshToken, session.getSessionExpiresAt());
    }

    /** Ends a session whose row lock the caller holds, and revokes every token of its family. */
    private boolean end(UUID sessionId, IssuedJwt.RevokedReason reason, Instant now) {
        boolean ended = sessionRepository.end(sessionId, now, reason) == 1;
        issuedJwtRepository.revokeSession(sessionId, now, reason);
        grantRepository.revokeBySession(sessionId, now);
        return ended;
    }
}
