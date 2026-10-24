package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorkerJwtIssuer {

    static final String WORKER_TOKEN_TYPE = "worker-session+jwt";
    static final String JOB_TOKEN_TYPE = "job+jwt";
    static final String WORK_DEADLINE_CLAIM = "work_deadline";
    static final String TURN_TOKEN_TYPE = "turn+jwt";

    /**
     * Lets a sandbox call the review model, the direct models of its practice programs and the other gateway
     * capabilities of its job. A job holds one such credential.
     */
    public static final String LLM_PROXY_SCOPE = "llm_proxy";

    private final WorkerKeyRing keyRing;
    private final WorkerTokenProperties properties;

    public WorkerJwtIssuer(WorkerKeyRing keyRing, WorkerTokenProperties properties) {
        this.keyRing = keyRing;
        this.properties = properties;
    }

    public IssuedWorkerJwt issue(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        WorkerSigningKey active = keyRing.active();
        Algorithm algorithm = Algorithm.RSA256(active.publicKey(), active.privateKey());
        Instant now = Instant.now();
        Instant expires = now.plus(properties.ttl());
        String jti = UUID.randomUUID().toString();
        String token = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", WORKER_TOKEN_TYPE))
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withSubject(workerId)
                .withJWTId(jti)
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expires)
                .sign(algorithm);
        return new IssuedWorkerJwt(token, jti, expires);
    }

    /**
     * A job credential that stays valid until {@code expiresAt} and names {@code workDeadline}, the end of the
     * attempt's work. Both are whole seconds when signed; the deadline must not be later than the expiry.
     */
    public String issueForJobUntil(
            UUID jobId,
            Long workspaceId,
            int attempt,
            Instant workDeadline,
            Instant expiresAt,
            JobCredentialScope scope) {
        WorkerSigningKey active = keyRing.active();
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        if (!workDeadline.isAfter(now) || workDeadline.isAfter(expiresAt))
            throw new IllegalArgumentException("workDeadline must be in the future and not after expiresAt");
        return JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", JOB_TOKEN_TYPE))
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withClaim("job_id", jobId.toString())
                .withClaim("workspace_id", workspaceId)
                .withClaim("attempt", attempt)
                .withClaim(WORK_DEADLINE_CLAIM, workDeadline)
                .withClaim("scope", List.of(LLM_PROXY_SCOPE, scope.claim()))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));
    }

    /** A Heph turn attempt's credential; it expires at {@code expiresAt}, which the caller derives from the deadline. */
    public String issueForTurn(
            UUID turnId, Long workspaceId, Long accountId, UUID conversationId, int attempt, Instant expiresAt) {
        WorkerSigningKey active = keyRing.active();
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        return JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", TURN_TOKEN_TYPE))
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withClaim("turn_id", turnId.toString())
                .withClaim("workspace_id", workspaceId)
                .withClaim("account_id", accountId)
                .withClaim("conversation_id", conversationId.toString())
                .withClaim("attempt", attempt)
                .withClaim("scope", List.of("llm_proxy"))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expiresAt)
                .sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));
    }

    public record IssuedWorkerJwt(String token, String jti, Instant expiresAt) {}
}
