package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class WorkerJwtIssuer {

    static final String WORKER_TOKEN_TYPE = "worker-session+jwt";
    static final String JOB_TOKEN_TYPE = "job+jwt";

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

    public String issueForJob(UUID jobId, Long workspaceId, int attempt, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("ttl must be positive");
        return issueForJobUntil(jobId, workspaceId, attempt, Instant.now().plus(ttl));
    }

    public String issueForJobUntil(UUID jobId, Long workspaceId, int attempt, Instant expiresAt) {
        WorkerSigningKey active = keyRing.active();
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        return JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", JOB_TOKEN_TYPE))
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withClaim("job_id", jobId.toString())
                .withClaim("workspace_id", workspaceId)
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
