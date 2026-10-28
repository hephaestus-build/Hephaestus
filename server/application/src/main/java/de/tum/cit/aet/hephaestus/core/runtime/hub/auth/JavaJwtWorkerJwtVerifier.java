package de.tum.cit.aet.hephaestus.core.runtime.hub.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Arrays;
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
                && !WorkerJwtIssuer.ATTEMPT_TOKEN_TYPE.equals(tokenType)) {
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
        if (WorkerJwtIssuer.ATTEMPT_TOKEN_TYPE.equals(tokenType)) {
            return attemptJwt(verified, workerId, jti, issuedAt, expiresAt);
        }
        if (workerId == null || workerId.isBlank()) {
            throw new WorkerJwtInvalidException("missing sub claim", "claim");
        }
        if (verified.getClaim(WorkerJwtIssuer.ID_CLAIM).asString() != null
                || !scopeClaim(verified).isEmpty()
                || longClaim(verified, "workspace_id") != null
                || !verified.getClaim(WorkerJwtIssuer.WORK_DEADLINE_CLAIM).isMissing()) {
            throw new WorkerJwtInvalidException("worker token contains attempt claims", "claim");
        }
        return new WorkerSessionJwt(workerId, jti, issuedAt, expiresAt);
    }

    private static AttemptJwt attemptJwt(
            DecodedJWT verified, @Nullable String workerId, String jti, Instant issuedAt, Instant expiresAt) {
        if (workerId != null) {
            throw new WorkerJwtInvalidException("attempt token contains sub claim", "claim");
        }
        String kindName = verified.getClaim(WorkerJwtIssuer.KIND_CLAIM).asString();
        AttemptJwt.Kind kind = Arrays.stream(AttemptJwt.Kind.values())
                .filter(candidate -> candidate.name().equals(kindName))
                .findFirst()
                .orElseThrow(() -> new WorkerJwtInvalidException("missing or invalid attempt_kind claim", "claim"));
        UUID id = uuidClaim(verified, WorkerJwtIssuer.ID_CLAIM);
        Long workspaceId = longClaim(verified, "workspace_id");
        Integer attempt = intClaim(verified, "attempt");
        if (id == null || workspaceId == null || attempt == null) {
            throw new WorkerJwtInvalidException("missing or invalid attempt binding claim", "claim");
        }
        // A review credential without its signed work deadline has no defined work time. The JWT leeway applies to
        // the credential's own times, never to this claim.
        Instant workDeadline = numericDateClaim(verified, WorkerJwtIssuer.WORK_DEADLINE_CLAIM);
        if ((kind == AttemptJwt.Kind.REVIEW) != (workDeadline != null)
                || (workDeadline == null
                        && !verified.getClaim(WorkerJwtIssuer.WORK_DEADLINE_CLAIM)
                                .isMissing())) {
            throw new WorkerJwtInvalidException(
                    "a review token, and only a review token, has a work_deadline", "claim");
        }
        try {
            return new AttemptJwt(
                    kind, id, workspaceId, attempt, scopeClaim(verified), jti, issuedAt, expiresAt, workDeadline);
        } catch (IllegalArgumentException invalid) {
            throw new WorkerJwtInvalidException("invalid attempt claims: " + invalid.getMessage(), "claim", invalid);
        }
    }

    // A NumericDate is a JSON number of seconds (RFC 7519 §2); java-jwt answers null for any other value.
    private static @Nullable Instant numericDateClaim(DecodedJWT jwt, String name) {
        try {
            return jwt.getClaim(name).asInstant();
        } catch (RuntimeException e) {
            throw new WorkerJwtInvalidException("invalid " + name + " claim", "claim", e);
        }
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
