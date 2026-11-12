package de.tum.cit.aet.hephaestus.agent.attempt;

import com.auth0.jwt.JWT;
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

/** Checks attempt tokens that any live worker's {@link AttemptTokenIssuer} signed. */
public class AttemptTokenVerifier {

    private static final String EXPECTED_ALG = "RS256";

    private final AttemptKeyring keyring;
    private final MeterRegistry meterRegistry;

    public AttemptTokenVerifier(AttemptKeyring keyring, MeterRegistry meterRegistry) {
        this.keyring = keyring;
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

    /**
     * Checks a sandbox token: the credential of one sandbox for its subject, which the gateway then checks against the
     * sandbox's live row. An attempt token is no sandbox token, nor the other way round.
     */
    public SandboxJwt verifySandbox(String token) {
        try {
            DecodedJWT verified = verifySigned(token, AttemptTokenIssuer.SANDBOX_TOKEN_TYPE);
            if (verified.getSubject() != null)
                throw new AttemptTokenInvalidException("sandbox token contains sub claim", "claim");
            UUID sandboxId = uuidClaim(verified, AttemptTokenIssuer.SANDBOX_CLAIM);
            Long workspaceId = longClaim(verified, "workspace_id");
            String subjectKey =
                    verified.getClaim(AttemptTokenIssuer.SUBJECT_CLAIM).asString();
            if (sandboxId == null || workspaceId == null || subjectKey == null || subjectKey.isBlank())
                throw new AttemptTokenInvalidException("missing or invalid sandbox binding claim", "claim");
            SandboxJwt jwt = new SandboxJwt(
                    sandboxId, workspaceId, subjectKey, verified.getId(), verified.getExpiresAtAsInstant());
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
        DecodedJWT verified = verifySigned(token, AttemptTokenIssuer.ATTEMPT_TOKEN_TYPE);
        return attemptJwt(
                verified,
                verified.getSubject(),
                verified.getId(),
                verified.getIssuedAtAsInstant(),
                verified.getExpiresAtAsInstant());
    }

    /** The signature, the algorithm, the key, the type and the registered claims that every token of this keyring has. */
    private DecodedJWT verifySigned(@Nullable String token, String type) {
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
        JWTVerifier verifier = keyring.verifierFor(tokenKid);
        if (verifier == null) {
            throw new AttemptTokenInvalidException("unknown kid", "kid");
        }
        DecodedJWT verified;
        try {
            verified = verifier.verify(token);
        } catch (JWTVerificationException e) {
            throw new AttemptTokenInvalidException(
                    "verify failed: " + e.getClass().getSimpleName(), "sig", e);
        }
        if (!type.equals(verified.getType())) {
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
        return verified;
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
        UUID runId = uuidClaim(verified, AttemptTokenIssuer.RUN_CLAIM);
        Long workspaceId = longClaim(verified, "workspace_id");
        Integer epoch = intClaim(verified, AttemptTokenIssuer.EPOCH_CLAIM);
        if (id == null || workspaceId == null || epoch == null || runId == null) {
            throw new AttemptTokenInvalidException("missing or invalid attempt binding claim", "claim");
        }
        // A credential without its signed work deadline has no defined work time. The JWT leeway applies to the
        // credential's own times, never to this claim.
        Instant workDeadline = numericDateClaim(verified, AttemptTokenIssuer.WORK_DEADLINE_CLAIM);
        if (workDeadline == null) {
            throw new AttemptTokenInvalidException("missing or invalid work_deadline claim", "claim");
        }
        try {
            return new AttemptJwt(
                    kind, id, workspaceId, epoch, runId, scopeClaim(verified), jti, issuedAt, expiresAt, workDeadline);
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
