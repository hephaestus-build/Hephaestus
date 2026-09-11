package de.tum.cit.aet.hephaestus.integration.core.oauth.state;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Default {@link OAuthStateService} impl: HMAC-SHA256 over
 * {@code workspaceId | kind | issuedAt | nonce | actorSegment} (five pipe-delimited
 * fields; {@code actorSegment} is the base64url-encoded actorRef, empty when null),
 * base64url-encoded. The emitted token appends the signature as a sixth segment, so
 * {@link #consume} splits on exactly six parts.
 *
 * <p>Verifies the MAC + freshness; rejects expired tokens (10-minute TTL by default).
 * The nonce makes every issued state unique, so even simultaneous concurrent OAuth
 * flows produce distinguishable tokens. Constant-time MAC comparison.
 *
 * <p><b>Single-use guarantee.</b> Every {@link #issue} writes a row to
 * {@link OAuthStateNonceStore}; every {@link #consume} attempts an atomic
 * conditional UPDATE on that row. The first caller wins; the second sees zero
 * rows affected and is rejected with {@code "OAuth state already consumed"}.
 * This closes the replay window inside the TTL.
 *
 * <p>{@link OAuthStateNonceStore} is optional in the constructor so the
 * unit-test overloads (no DB) still work; production wiring always supplies it.
 */
@ConditionalOnServerRole
@Component
public class HmacOAuthStateService implements OAuthStateService {

    private static final Logger log = LoggerFactory.getLogger(HmacOAuthStateService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final byte[] secret;
    private final Duration ttl;

    @Nullable
    private final OAuthStateNonceStore nonceStore;

    /** Test factory: HMAC + TTL only, no single-use enforcement. */
    public static HmacOAuthStateService withoutNonceStore(@Nullable String secret, Duration ttl) {
        return new HmacOAuthStateService(secret, ttl, (OAuthStateNonceStore) null);
    }

    /** Test factory: HMAC + TTL + custom nonce store. */
    public static HmacOAuthStateService withNonceStore(String secret, Duration ttl, OAuthStateNonceStore store) {
        return new HmacOAuthStateService(secret, ttl, store);
    }

    /**
     * Spring-injected production constructor. The HMAC secret + TTL bind via
     * {@link OAuthStateProperties}; the {@code nonceStore} provides the single-use guarantee on
     * top of HMAC + TTL.
     *
     * <p>If {@code hephaestus.integration.oauth-state.secret} is unset, falls back to the shared
     * {@code hephaestus.webhook.secret} (pre-existing infrastructure secret). When neither is
     * configured, production ({@code prod} profile) fails fast; outside production an ephemeral
     * random secret is generated with a WARN so local development boots without
     * webhook/OAuth config — mirroring the {@code WorkerSigningKey} dev-vs-prod contract.
     *
     * <p>{@code @Autowired} is required to disambiguate from the private core constructor (used by
     * the static test factories): with two declared constructors and no marker, Spring falls back
     * to a non-existent no-arg constructor.
     */
    @Autowired
    public HmacOAuthStateService(
            OAuthStateProperties properties,
            WebhookProperties webhookProperties,
            Environment environment,
            @Nullable OAuthStateNonceStore nonceStore) {
        this(resolveSecret(properties, webhookProperties, environment), properties.ttl(), nonceStore);
    }

    private static String resolveSecret(
            OAuthStateProperties properties, WebhookProperties webhookProperties, Environment environment) {
        String configured = properties.secret();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String webhookSecret = webhookProperties.secret();
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            return webhookSecret;
        }
        if (environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "Set hephaestus.integration.oauth-state.secret (or hephaestus.webhook.secret) — required for OAuth state HMAC in production.");
        }
        if (environment.matchesProfiles("specs", "cds-training")) {
            log.debug("Created an ephemeral OAuth-state secret for artifact generation");
        } else {
            log.warn(
                    "No hephaestus.integration.oauth-state.secret / hephaestus.webhook.secret configured; "
                            + "generating an EPHEMERAL dev-only OAuth-state secret. State tokens won't survive a restart "
                            + "and webhook HMAC will not match any vendor secret — set the secret for real integration testing.");
        }
        byte[] ephemeral = new byte[32];
        RANDOM.nextBytes(ephemeral);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeral);
    }

    /**
     * Core constructor (shared by the Spring path and the test factories). Validates that a secret
     * is present — same fail-fast contract as before.
     */
    private HmacOAuthStateService(
            @Nullable String configuredSecret, @Nullable Duration ttl, @Nullable OAuthStateNonceStore nonceStore) {
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new IllegalStateException(
                    "Set hephaestus.integration.oauth-state.secret (or hephaestus.webhook.secret) — required for OAuth state HMAC.");
        }
        this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl == null ? DEFAULT_TTL : ttl;
        this.nonceStore = nonceStore;
    }

    @Override
    public String issue(long workspaceId, IntegrationKind kind) {
        return issue(workspaceId, kind, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The actorRef is encoded as a base64url segment so it survives the {@code |}
     * tokeniser intact even if a future identity source emits subjects containing the
     * delimiter. {@code null} → empty segment, which decodes back to {@code null} in
     * {@link #consume(String)} — preserving the binding-field nullability contract.
     */
    @Override
    public String issue(long workspaceId, IntegrationKind kind, @Nullable String actorRef) {
        long issuedAt = Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[12];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String actorSegment = encodeActor(actorRef);
        String payload = workspaceId + "|" + kind.name() + "|" + issuedAt + "|" + nonce + "|" + actorSegment;
        String sig = hmac(payload);
        // Persist the nonce BEFORE returning so a fast OAuth roundtrip can't race the
        // first consume to an empty row. Skipped when no store is wired (test path).
        if (nonceStore != null) {
            nonceStore.issue(nonce, workspaceId, kind, Instant.ofEpochSecond(issuedAt));
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public StateBinding consume(@Nullable String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("OAuth state missing");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OAuth state malformed", e);
        }
        // -1 limit preserves the trailing empty actorSegment that {@link #issue} writes
        // when actorRef is null. Without -1 a trailing empty string is dropped and the
        // arity check below misfires.
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("OAuth state malformed");
        }
        String workspaceIdStr = parts[0];
        String kindStr = parts[1];
        String issuedAtStr = parts[2];
        String nonce = parts[3];
        String actorSegment = parts[4];
        String suppliedSig = parts[5];
        String payload = workspaceIdStr + "|" + kindStr + "|" + issuedAtStr + "|" + nonce + "|" + actorSegment;
        String expectedSig = hmac(payload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8), suppliedSig.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("OAuth state signature mismatch");
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(issuedAtStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OAuth state issuedAt malformed", e);
        }
        Instant issued = Instant.ofEpochSecond(issuedAt);
        if (Instant.now().minus(ttl).isAfter(issued)) {
            throw new IllegalArgumentException("OAuth state expired");
        }
        IntegrationKind kind;
        try {
            kind = IntegrationKind.valueOf(kindStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OAuth state references unknown kind: " + kindStr, e);
        }
        long workspaceId;
        try {
            workspaceId = Long.parseLong(workspaceIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OAuth state workspaceId malformed", e);
        }
        // Single-use enforcement via atomic UPDATE inside tryConsume. The HMAC + TTL are
        // already verified — any forged or stale token has been rejected.
        if (nonceStore != null && !nonceStore.tryConsume(nonce)) {
            throw new IllegalArgumentException("OAuth state already consumed");
        }
        String actorRef = decodeActor(actorSegment);
        return new StateBinding(workspaceId, kind, issued, actorRef);
    }

    private static String encodeActor(@Nullable String actorRef) {
        if (actorRef == null || actorRef.isEmpty()) return "";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(actorRef.getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static String decodeActor(String actorSegment) {
        if (actorSegment.isEmpty()) return null;
        try {
            return new String(Base64.getUrlDecoder().decode(actorSegment), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] tag = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
