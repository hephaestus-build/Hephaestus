package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class WorkerJwtIssuer {

    static final String WORKER_TOKEN_TYPE = "worker-session+jwt";
    static final String ATTEMPT_TOKEN_TYPE = "attempt+jwt";
    static final String KIND_CLAIM = "attempt_kind";
    static final String ID_CLAIM = "attempt_id";
    static final String WORK_DEADLINE_CLAIM = "work_deadline";

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
     * An attempt's credential that stays valid until {@code expiresAt}. A review attempt's names {@code workDeadline},
     * the end of its work; both are whole seconds when signed, and the deadline must not be later than the expiry. A
     * turn attempt's has none: the caller derives its expiry from the turn's deadline.
     */
    public String issueForAttempt(
            AttemptJwt.Kind kind,
            UUID id,
            Long workspaceId,
            int attempt,
            @Nullable Instant workDeadline,
            Instant expiresAt) {
        WorkerSigningKey active = keyRing.active();
        Instant now = Instant.now();
        if (!expiresAt.isAfter(now)) throw new IllegalArgumentException("expiresAt must be in the future");
        if ((kind == AttemptJwt.Kind.REVIEW) != (workDeadline != null))
            throw new IllegalArgumentException("only a review attempt has a work deadline");
        if (workDeadline != null && (!workDeadline.isAfter(now) || workDeadline.isAfter(expiresAt)))
            throw new IllegalArgumentException("workDeadline must be in the future and not after expiresAt");
        var token = JWT.create()
                .withHeader(Map.of("kid", active.kid(), "typ", ATTEMPT_TOKEN_TYPE))
                .withIssuer(properties.issuer())
                .withAudience(properties.audience())
                .withClaim(KIND_CLAIM, kind.name())
                .withClaim(ID_CLAIM, id.toString())
                .withClaim("workspace_id", workspaceId)
                .withClaim("attempt", attempt)
                .withClaim("scope", List.of(LLM_PROXY_SCOPE))
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(now)
                .withNotBefore(now)
                .withExpiresAt(expiresAt);
        if (workDeadline != null) token = token.withClaim(WORK_DEADLINE_CLAIM, workDeadline);
        return token.sign(Algorithm.RSA256(active.publicKey(), active.privateKey()));
    }

    public record IssuedWorkerJwt(String token, String jti, Instant expiresAt) {}
}
