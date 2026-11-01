package de.tum.cit.aet.hephaestus.agent.attempt;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Checks the attempt tokens that this worker's {@link AttemptTokenIssuer} signed. */
public class AttemptTokenVerifier {

    private static final String EXPECTED_ALG = "RS256";

    private final String kid;
    private final JWTVerifier verifier;
    private final MeterRegistry meterRegistry;

    public AttemptTokenVerifier(AttemptSigningKey key, MeterRegistry meterRegistry) {
        this.kid = key.kid();
        this.verifier = JWT.require(Algorithm.RSA256(key.publicKey(), null))
                .withIssuer(AttemptTokenIssuer.ISSUER)
                .withAudience(AttemptTokenIssuer.AUDIENCE)
                .acceptLeeway(5)
                .build();
        this.meterRegistry = meterRegistry;
    }

    public AttemptJwt verify(String token) {
        try {
            AttemptJwt jwt = verifyInternal(token);
            meterRegistry
                    .counter(AgentMetrics.ATTEMPT_TOKEN_VERIFY, "outcome", "success")
                    .increment();
            return jwt;
        } catch (AttemptTokenInvalidException e) {
            meterRegistry
                    .counter(AgentMetrics.ATTEMPT_TOKEN_VERIFY, "outcome", "failed", "reason", e.getReasonTag())
                    .increment();
            throw e;
        }
    }

    private AttemptJwt verifyInternal(String token) {
        if (token == null || token.isBlank()) {
            throw new AttemptTokenInvalidException("token missing", "missing");
        }
        DecodedJWT decoded;
        try {
            decoded = JWT.decode(token);
        } catch (JWTVerificationException e) {
            throw new AttemptTokenInvalidException(
                    "decode failed: " + e.getClass().getSimpleName(), "decode", e);
        }
        // Reject the untrusted header algorithm before verifying (RFC 8725 §3.1).
        if (!EXPECTED_ALG.equals(decoded.getAlgorithm())) {
            throw new AttemptTokenInvalidException("alg not allowed: " + decoded.getAlgorithm(), "alg");
        }
        String tokenKid = decoded.getKeyId();
        if (tokenKid == null || tokenKid.isBlank()) {
            throw new AttemptTokenInvalidException("missing kid header", "kid");
        }
        if (!kid.equals(tokenKid)) {
            throw new AttemptTokenInvalidException("unknown kid: " + tokenKid, "kid");
        }
        DecodedJWT verified;
        try {
            verified = verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new AttemptTokenInvalidException(
                    "verify failed: " + e.getClass().getSimpleName(), "sig", e);
        }
        if (!AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE.equals(verified.getType())) {
            throw new AttemptTokenInvalidException("token type not allowed", "typ");
        }
        String jti = verified.getId();
        Instant issuedAt = verified.getIssuedAtAsInstant();
        Instant expiresAt = verified.getExpiresAtAsInstant();
        if (jti == null || jti.isBlank()) {
            throw new AttemptTokenInvalidException("missing jti claim", "claim");
        }
        if (expiresAt == null) {
            throw new AttemptTokenInvalidException("missing exp claim", "claim");
        }
        if (issuedAt == null) {
            throw new AttemptTokenInvalidException("missing iat claim", "claim");
        }
        return attemptJwt(verified, verified.getSubject(), jti, issuedAt, expiresAt);
    }

    private static AttemptJwt attemptJwt(
            DecodedJWT verified, @Nullable String subject, String jti, Instant issuedAt, Instant expiresAt) {
        if (subject != null) {
            throw new AttemptTokenInvalidException("attempt token contains sub claim", "claim");
        }
        String kindName = verified.getClaim(AttemptTokenIssuer.KIND_CLAIM).asString();
        AttemptJwt.Kind kind = Arrays.stream(AttemptJwt.Kind.values())
                .filter(candidate -> candidate.name().equals(kindName))
                .findFirst()
                .orElseThrow(() -> new AttemptTokenInvalidException("missing or invalid attempt_kind claim", "claim"));
        UUID id = uuidClaim(verified, AttemptTokenIssuer.ID_CLAIM);
        UUID leaseId = uuidClaim(verified, AttemptTokenIssuer.LEASE_CLAIM);
        Long workspaceId = longClaim(verified, "workspace_id");
        Integer attempt = intClaim(verified, "attempt");
        if (id == null || workspaceId == null || attempt == null || leaseId == null) {
            throw new AttemptTokenInvalidException("missing or invalid attempt binding claim", "claim");
        }
        // A review credential without its signed work deadline has no defined work time. The JWT leeway applies to
        // the credential's own times, never to this claim.
        Instant workDeadline = numericDateClaim(verified, AttemptTokenIssuer.WORK_DEADLINE_CLAIM);
        if ((kind == AttemptJwt.Kind.REVIEW) != (workDeadline != null)
                || (workDeadline == null
                        && !verified.getClaim(AttemptTokenIssuer.WORK_DEADLINE_CLAIM)
                                .isMissing())) {
            throw new AttemptTokenInvalidException(
                    "a review token, and only a review token, has a work_deadline", "claim");
        }
        try {
            return new AttemptJwt(
                    kind,
                    id,
                    workspaceId,
                    attempt,
                    leaseId,
                    scopeClaim(verified),
                    jti,
                    issuedAt,
                    expiresAt,
                    workDeadline);
        } catch (IllegalArgumentException invalid) {
            throw new AttemptTokenInvalidException("invalid attempt claims: " + invalid.getMessage(), "claim", invalid);
        }
    }

    // A NumericDate is a JSON number of seconds (RFC 7519 §2); java-jwt answers null for any other value.
    private static @Nullable Instant numericDateClaim(DecodedJWT jwt, String name) {
        try {
            return jwt.getClaim(name).asInstant();
        } catch (RuntimeException e) {
            throw new AttemptTokenInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static @Nullable UUID uuidClaim(DecodedJWT jwt, String name) {
        try {
            String value = jwt.getClaim(name).asString();
            if (value == null) return null;
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new AttemptTokenInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static @Nullable Long longClaim(DecodedJWT jwt, String name) {
        try {
            Long value = jwt.getClaim(name).asLong();
            if (value == null || value <= 0) return null;
            return value;
        } catch (RuntimeException e) {
            throw new AttemptTokenInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static @Nullable Integer intClaim(DecodedJWT jwt, String name) {
        try {
            return jwt.getClaim(name).asInt();
        } catch (RuntimeException e) {
            throw new AttemptTokenInvalidException("invalid " + name + " claim", "claim", e);
        }
    }

    private static Set<String> scopeClaim(DecodedJWT jwt) {
        try {
            List<String> values = jwt.getClaim("scope").asList(String.class);
            return values == null ? Set.of() : Set.copyOf(values);
        } catch (RuntimeException e) {
            throw new AttemptTokenInvalidException("invalid scope claim", "claim", e);
        }
    }
}
