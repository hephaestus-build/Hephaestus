package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public class JavaJwtWorkerJwtVerifier implements WorkerJwtVerifier {

    private static final String EXPECTED_ALG = "RS256";

    private final Map<String, JWTVerifier> verifiersByKid;
    private final WorkerTokenDenylistService denylist;
    private final MeterRegistry meterRegistry;

    public JavaJwtWorkerJwtVerifier(
            WorkerKeyRing keyRing,
            WorkerTokenProperties properties,
            WorkerTokenDenylistService denylist,
            MeterRegistry meterRegistry) {
        Map<String, JWTVerifier> map = new HashMap<>();
        for (WorkerSigningKey key : keyRing.all()) {
            map.put(
                    key.kid(),
                    JWT.require(Algorithm.RSA256(key.publicKey(), null))
                            .withIssuer(properties.issuer())
                            .withAudience(properties.audience())
                            .acceptLeeway(5)
                            .build());
        }
        this.verifiersByKid = Map.copyOf(map);
        this.denylist = denylist;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public WorkerJwt verify(String token) {
        try {
            WorkerJwt jwt = verifyInternal(token);
            meterRegistry
                    .counter(CoreMetrics.WORKER_JWT_VERIFY, "outcome", "success")
                    .increment();
            return jwt;
        } catch (WorkerJwtInvalidException e) {
            meterRegistry
                    .counter(CoreMetrics.WORKER_JWT_VERIFY, "outcome", "failed", "reason", e.getReasonTag())
                    .increment();
            throw e;
        }
    }

    private WorkerJwt verifyInternal(String token) {
        if (token == null || token.isBlank()) {
            throw new WorkerJwtInvalidException("token missing", "missing");
        }
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            throw new WorkerJwtInvalidException("decode failed: " + e.getClass().getSimpleName(), "decode", e);
        }
        // Reject the untrusted header algorithm before selecting a verifier (RFC 8725 §3.1).
        if (!EXPECTED_ALG.equals(decoded.getAlgorithm())) {
            throw new WorkerJwtInvalidException("alg not allowed: " + decoded.getAlgorithm(), "alg");
        }
        String kid = decoded.getKeyId();
        if (kid == null || kid.isBlank()) {
            throw new WorkerJwtInvalidException("missing kid header", "kid");
        }
        JWTVerifier verifier = verifiersByKid.get(kid);
        if (verifier == null) {
            throw new WorkerJwtInvalidException("unknown kid: " + kid, "kid");
        }
        DecodedJWT verified;
        try {
            verified = verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new WorkerJwtInvalidException("verify failed: " + e.getClass().getSimpleName(), "sig", e);
        }
        String tokenType = verified.getType();
        if (!WorkerJwtIssuer.WORKER_TOKEN_TYPE.equals(tokenType)
                && !WorkerJwtIssuer.JOB_TOKEN_TYPE.equals(tokenType)
                && !WorkerJwtIssuer.TURN_TOKEN_TYPE.equals(tokenType)) {
            throw new WorkerJwtInvalidException("token type not allowed", "typ");
        }
        String workerId = verified.getSubject();
        String jti = verified.getId();
        Instant issuedAt = verified.getIssuedAtAsInstant();
        Instant expiresAt = verified.getExpiresAtAsInstant();
        if (jti == null || jti.isBlank()) {
            throw new WorkerJwtInvalidException("missing jti claim", "claim");
        }
        if (expiresAt == null) {
            throw new WorkerJwtInvalidException("missing exp claim", "claim");
        }
        if (issuedAt == null) {
            throw new WorkerJwtInvalidException("missing iat claim", "claim");
        }
        if (denylist.isRevoked(jti)) {
            throw new WorkerJwtInvalidException("token revoked", "revoked");
        }
        if (WorkerJwtIssuer.TURN_TOKEN_TYPE.equals(tokenType)) {
            return turnJwt(verified, workerId, jti, issuedAt, expiresAt);
        }
        UUID jobId = uuidClaim(verified, "job_id");
        if (WorkerJwtIssuer.WORKER_TOKEN_TYPE.equals(tokenType)) {
            if (workerId == null || workerId.isBlank()) {
                throw new WorkerJwtInvalidException("missing sub claim", "claim");
            }
            if (jobId != null || !scopeClaim(verified).isEmpty() || longClaim(verified, "workspace_id") != null) {
                throw new WorkerJwtInvalidException("worker token contains job claims", "claim");
            }
            return new WorkerSessionJwt(workerId, jti, issuedAt, expiresAt);
        }
        if (jobId == null) {
            throw new WorkerJwtInvalidException("missing job_id claim", "claim");
        }
        if (workerId != null) {
            throw new WorkerJwtInvalidException("job token contains sub claim", "claim");
        }
        Long workspaceId = longClaim(verified, "workspace_id");
        Integer attempt = intClaim(verified, "attempt");
        if (workspaceId == null || attempt == null || attempt < 0) {
            throw new WorkerJwtInvalidException("missing or invalid job binding claim", "claim");
        }
        return new JobJwt(jobId, workspaceId, attempt, scopeClaim(verified), jti, issuedAt, expiresAt);
    }

    private static TurnJwt turnJwt(
            DecodedJWT verified, @Nullable String workerId, String jti, Instant issuedAt, Instant expiresAt) {
        if (workerId != null || verified.getClaim("job_id").asString() != null) {
            throw new WorkerJwtInvalidException("turn token contains worker or job claims", "claim");
        }
        UUID turnId = uuidClaim(verified, "turn_id");
        UUID conversationId = uuidClaim(verified, "conversation_id");
        Long workspaceId = longClaim(verified, "workspace_id");
        Long userId = longClaim(verified, "user_id");
        Integer attempt = intClaim(verified, "attempt");
        if (turnId == null
                || conversationId == null
                || workspaceId == null
                || userId == null
                || attempt == null
                || attempt < 1) {
            throw new WorkerJwtInvalidException("missing or invalid turn binding claim", "claim");
        }
        return new TurnJwt(
                turnId, workspaceId, userId, conversationId, attempt, scopeClaim(verified), jti, issuedAt, expiresAt);
    }

    private static @Nullable UUID uuidClaim(DecodedJWT jwt, String name) {
        try {
            String value = jwt.getClaim(name).asString();
            if (value == null) return null;
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new WorkerJwtInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static @Nullable Long longClaim(DecodedJWT jwt, String name) {
        try {
            Long value = jwt.getClaim(name).asLong();
            if (value == null || value <= 0) return null;
            return value;
        } catch (RuntimeException e) {
            throw new WorkerJwtInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static @Nullable Integer intClaim(DecodedJWT jwt, String name) {
        try {
            return jwt.getClaim(name).asInt();
        } catch (RuntimeException e) {
            throw new WorkerJwtInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static Set<String> scopeClaim(DecodedJWT jwt) {
        try {
            List<String> values = jwt.getClaim("scope").asList(String.class);
            return values == null ? Set.of() : Set.copyOf(values);
        } catch (RuntimeException e) {
            throw new WorkerJwtInvalidException("invalid scope claim", "claim", e);
        }
    }
}
