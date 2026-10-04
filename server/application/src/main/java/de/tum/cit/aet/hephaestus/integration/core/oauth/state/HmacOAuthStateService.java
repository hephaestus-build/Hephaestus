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
 * {@code v2 | workspaceId | kind | issuedAt | nonce | actorSegment}.
 * The seventh segment is the signature. Versioning prevents a historical display login
 * in an older signed token from being interpreted as an account id.
 *
 * <p>Verifies the MAC + freshness; rejects expired tokens (10-minute TTL by default).
 * The nonce makes every issued state unique, so even simultaneous concurrent OAuth
 * flows produce distinguishable tokens. Constant-time MAC comparison.
 *
 * <p><b>Single-use guarantee.</b> Every {@link #issue} writes a row to
 * {@link OAuthStateNonceStore}; every {@link #consume} attempts an atomic
 * conditional UPDATE on that row. The first caller wins; the second sees zero
 * rows affected and is rejected as already used.
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
                    "Set hephaestus.integration.oauth-state.secret or hephaestus.webhook.secret. Production needs one of them for the OAuth state HMAC.");
        }
        if (environment.matchesProfiles("specs", "cds-training")) {
            log.debug("Created an ephemeral OAuth-state secret for artifact generation");
        } else {
            log.warn(
                    "Neither hephaestus.integration.oauth-state.secret nor hephaestus.webhook.secret is configured. "
                            + "Hephaestus generates a temporary dev-only OAuth-state secret. State tokens do not survive a restart. "
                            + "The webhook HMAC does not match any vendor secret. For real integration testing, set the secret.");
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
                    "Set hephaestus.integration.oauth-state.secret or hephaestus.webhook.secret. The OAuth state HMAC needs one of them.");
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
     * <p>The actorAccountId is encoded as a base64url segment so it survives the {@code |}
     * tokeniser intact. It contains only a decimal account id. {@code null} → empty segment, which decodes back to {@code null} in
     * {@link #consume(String)} — preserving the binding-field nullability contract.
     */
    @Override
    public String issue(long workspaceId, IntegrationKind kind, @Nullable Long actorAccountId) {
        long issuedAt = Instant.now().getEpochSecond();
        byte[] nonceBytes = new byte[12];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String actorSegment = encodeActor(actorAccountId);
        String payload = "v2|" + workspaceId + "|" + kind.name() + "|" + issuedAt + "|" + nonce + "|" + actorSegment;
        String sig = hmac(payload);
        // Persist the nonce BEFORE returning so a fast OAuth roundtrip can't race the
        // first consume to an empty row. Skipped when no store is wired (test path).
        if (nonceStore != null) {
            nonceStore.issue(nonce, workspaceId, kind, Instant.ofEpochSecond(issuedAt), actorAccountId);
        }
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((payload + "|" + sig).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public StateBinding consume(@Nullable String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("The OAuth state is missing.");
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The OAuth state has an incorrect format.", e);
        }
        // -1 limit preserves the trailing empty actorSegment that {@link #issue} writes
        // when actorAccountId is null. Without -1 a trailing empty string is dropped and the
        // arity check below misfires.
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != 7 || !"v2".equals(parts[0])) {
            throw new IllegalArgumentException("The OAuth state has an incorrect format.");
        }
        String workspaceIdStr = parts[1];
        String kindStr = parts[2];
        String issuedAtStr = parts[3];
        String nonce = parts[4];
        String actorSegment = parts[5];
        String suppliedSig = parts[6];
        String payload = "v2|" + workspaceIdStr + "|" + kindStr + "|" + issuedAtStr + "|" + nonce + "|" + actorSegment;
        String expectedSig = hmac(payload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8), suppliedSig.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The signature of the OAuth state is not correct.");
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(issuedAtStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The issuedAt value of the OAuth state has an incorrect format.", e);
        }
        Instant issued = Instant.ofEpochSecond(issuedAt);
        if (Instant.now().minus(ttl).isAfter(issued)) {
            throw new IllegalArgumentException("The OAuth state expired.");
        }
        IntegrationKind kind;
        try {
            kind = IntegrationKind.valueOf(kindStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The OAuth state names an unknown kind: " + kindStr, e);
        }
        long workspaceId;
        try {
            workspaceId = Long.parseLong(workspaceIdStr);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The workspaceId value of the OAuth state has an incorrect format.", e);
        }
        Long actorAccountId = decodeActor(actorSegment);
        StateBinding binding = new StateBinding(workspaceId, kind, issued, actorAccountId);
        // Single-use enforcement via atomic UPDATE inside tryConsume. The HMAC + TTL are
        // already verified — any forged or stale token has been rejected.
        if (nonceStore != null && !nonceStore.tryConsume(nonce, binding)) {
            throw new IllegalArgumentException("The OAuth state was already used.");
        }
        return binding;
    }

    private static String encodeActor(@Nullable Long actorAccountId) {
        if (actorAccountId == null) return "";
        if (actorAccountId <= 0)
            throw new IllegalArgumentException("The actor value of the OAuth state has an incorrect format.");
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(actorAccountId.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Nullable
    private static Long decodeActor(String actorSegment) {
        if (actorSegment.isEmpty()) return null;
        try {
            String value = new String(Base64.getUrlDecoder().decode(actorSegment), StandardCharsets.UTF_8);
            long accountId = Long.parseLong(value);
            if (accountId <= 0 || !Long.toString(accountId).equals(value)) {
                throw new IllegalArgumentException("The actor value of the OAuth state has an incorrect format.");
            }
            return accountId;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The actor value of the OAuth state has an incorrect format.", e);
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
