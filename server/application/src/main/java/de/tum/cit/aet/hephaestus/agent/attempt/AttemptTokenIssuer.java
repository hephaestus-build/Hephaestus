package de.tum.cit.aet.hephaestus.agent.attempt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Issues the attempt tokens of this worker's sandboxes; {@link AttemptTokenVerifier} checks them. */
public class AttemptTokenIssuer {

    static final String ISSUER = "hephaestus-worker";
    static final String AUDIENCE = "hephaestus-sandbox";
    static final String ATTEMPT_TOKEN_TYPE = "attempt+jwt";
    static final String KIND_CLAIM = "attempt_kind";
    static final String ID_CLAIM = "attempt_id";
    static final String LEASE_CLAIM = "lease_id";
    static final String WORK_DEADLINE_CLAIM = "work_deadline";

    /**
     * Lets a sandbox call the review model, the direct models of its practice programs and the other gateway
     * capabilities of its job. A job holds one such credential.
     */
    public static final String LLM_PROXY_SCOPE = "llm_proxy";

    private final AttemptSigningKey key;

    public AttemptTokenIssuer(AttemptSigningKey key) {
        this.key = key;
    }

    /**
     * A review attempt's credential that stays valid until {@code expiresAt}. It names {@code workDeadline}, the end of
     * its work; both are whole seconds when signed, and the deadline must not be later than the expiry.
     */
    public String issueForReview(
            UUID id, Long workspaceId, int attempt, UUID leaseId, Instant workDeadline, Instant expiresAt) {
        if (!workDeadline.isAfter(Instant.now()) || workDeadline.isAfter(expiresAt))
            throw new IllegalArgumentException("workDeadline must be in the future and not after expiresAt");
        return claims(AttemptJwt.Kind.REVIEW, id, workspaceId, attempt, leaseId, expiresAt)
                .withClaim(WORK_DEADLINE_CLAIM, workDeadline)
                .sign(Algorithm.RSA256(key.publicKey(), key.privateKey()));
    }

    /** A turn attempt's credential that stays valid until {@code expiresAt}, which the caller derives from the turn. */
    public String issueForTurn(UUID id, Long workspaceId, int attempt, UUID leaseId, Instant expiresAt) {
        return claims(AttemptJwt.Kind.TURN, id, workspaceId, attempt, leaseId, expiresAt)
                .sign(Algorithm.RSA256(key.publicKey(), key.privateKey()));
    }

    private JWTCreator.Builder claims(
            AttemptJwt.Kind kind, UUID id, Long workspaceId, int attempt, UUID leaseId, Instant expiresAt) {
        Objects.requireNonNull(leaseId, "leaseId");
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        return JWT.create()
                .withHeader(Map.of("kid", key.kid(), "typ", ATTEMPT_TOKEN_TYPE))
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
