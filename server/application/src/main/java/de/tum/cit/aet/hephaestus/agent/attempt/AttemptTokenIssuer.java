package de.tum.cit.aet.hephaestus.agent.attempt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Issues the attempt tokens and sandbox tokens of this worker's sandboxes; {@link AttemptTokenVerifier} checks them on
 * any worker.
 */
public class AttemptTokenIssuer {

    static final String ISSUER = "hephaestus-worker";
    static final String AUDIENCE = "hephaestus-sandbox";
    static final String ATTEMPT_TOKEN_TYPE = "attempt+jwt";
    static final String KIND_CLAIM = "attempt_kind";
    static final String ID_CLAIM = "attempt_id";
    static final String LEASE_CLAIM = "lease_id";
    static final String WORK_DEADLINE_CLAIM = "work_deadline";
    static final String SANDBOX_TOKEN_TYPE = "sandbox+jwt";
    static final String SANDBOX_CLAIM = "sandbox_id";
    static final String SUBJECT_CLAIM = "subject_key";

    /**
     * Lets a sandbox call the review model, the direct models of its practice scripts and the other gateway
     * capabilities of its job. A job holds one such credential.
     */
    public static final String LLM_PROXY_SCOPE = "llm_proxy";

    private final AttemptKeyring keyring;

    public AttemptTokenIssuer(AttemptKeyring keyring) {
        this.keyring = keyring;
    }

    /**
     * A review attempt's credential that stays valid until {@code expiresAt}. It names {@code workDeadline}, the end of
     * its work; both are whole seconds when signed, and the deadline must not be later than the expiry.
     */
    public String issueForReview(
            UUID id, Long workspaceId, int attempt, UUID leaseId, Instant workDeadline, Instant expiresAt) {
        return issue(AttemptJwt.Kind.REVIEW, id, workspaceId, attempt, leaseId, workDeadline, expiresAt);
    }

    /**
     * A turn attempt's credential. Its work ends at the turn's deadline; it stays valid until {@code expiresAt}, so the
     * attempt can still end after a stop.
     */
    public String issueForTurn(
            UUID id, Long workspaceId, int attempt, UUID leaseId, Instant workDeadline, Instant expiresAt) {
        return issue(AttemptJwt.Kind.TURN, id, workspaceId, attempt, leaseId, workDeadline, expiresAt);
    }

    private String issue(
            AttemptJwt.Kind kind,
            UUID id,
            Long workspaceId,
            int attempt,
            UUID leaseId,
            Instant workDeadline,
            Instant expiresAt) {
        if (!workDeadline.isAfter(Instant.now()) || workDeadline.isAfter(expiresAt))
            throw new IllegalArgumentException("workDeadline must be in the future and not after expiresAt");
        return claims(kind, id, workspaceId, attempt, leaseId, expiresAt)
                .withClaim(WORK_DEADLINE_CLAIM, workDeadline)
                .sign(algorithm());
    }

    /**
     * A sandbox's credential: it names the sandbox, its workspace and the one subject it serves, and it expires with
     * the sandbox at {@code expiresAt}. It carries no run: each call names its run and epoch, and the gateway checks
     * them against the subject. It has no scope, because only the agent gateway takes it.
     */
    public String issueForSandbox(UUID sandboxId, long workspaceId, String subjectKey, Instant expiresAt) {
        if (subjectKey.isBlank()) throw new IllegalArgumentException("subjectKey must not be blank");
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        return JWT.create()
                .withHeader(Map.of("kid", keyring.signingKey().kid(), "typ", SANDBOX_TOKEN_TYPE))
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim(SANDBOX_CLAIM, sandboxId.toString())
                .withClaim("workspace_id", workspaceId)
                .withClaim(SUBJECT_CLAIM, subjectKey)
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expiresAt)
                .sign(algorithm());
    }

    /** How long a sandbox token may live: as long as its signing key's published public key does. */
    public Duration longestSandboxToken() {
        return AttemptKeyring.KEPT_FOR;
    }

    private Algorithm algorithm() {
        AttemptSigningKey key = keyring.signingKey();
        return Algorithm.RSA256(key.publicKey(), key.privateKey());
    }

    private JWTCreator.Builder claims(
            AttemptJwt.Kind kind, UUID id, Long workspaceId, int attempt, UUID leaseId, Instant expiresAt) {
        Objects.requireNonNull(leaseId, "leaseId");
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        return JWT.create()
                .withHeader(Map.of("kid", keyring.signingKey().kid(), "typ", ATTEMPT_TOKEN_TYPE))
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim(KIND_CLAIM, kind.name())
                .withClaim(ID_CLAIM, id.toString())
                .withClaim("workspace_id", workspaceId)
                .withClaim("attempt", attempt)
                .withClaim(LEASE_CLAIM, leaseId.toString())
                .withClaim("scope", List.of(LLM_PROXY_SCOPE))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expiresAt);
    }
}
