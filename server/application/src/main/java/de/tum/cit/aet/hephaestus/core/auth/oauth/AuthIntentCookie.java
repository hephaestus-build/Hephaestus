package de.tum.cit.aet.hephaestus.core.auth.oauth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short-lived signed cookie that carries the user's pre-OAuth intent across the IdP
 * round-trip: which workspace to land back in, where to redirect after success, and
 * whether this is a fresh login or a link-mode flow.
 *
 * <p>Distinct from {@link CookieOAuth2AuthorizationRequestRepository}'s cookie (which
 * holds Spring's serialised {@code OAuth2AuthorizationRequest}). Separate concerns,
 * separate AADs, separate cookies.
 *
 * <h2>Cookie</h2>
 * Name {@value #COOKIE_NAME}; {@code HttpOnly}, {@code Secure}, {@code SameSite=Lax},
 * {@code Path=/}, 10-minute TTL.
 *
 * <h2>Encryption</h2>
 * AES-256-GCM, AAD = {@code "auth-intent"} (distinct from {@code "oauth2-state"}).
 */
public class AuthIntentCookie {

    public static final String COOKIE_NAME = "__Host-AUTH_INTENT";
    private static final String AAD = "auth-intent";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_COOKIE_AGE_SECONDS = 600;
    private static final Logger log = LoggerFactory.getLogger(AuthIntentCookie.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretKeySpec key;
    private final Clock clock;

    public AuthIntentCookie(byte[] aesKeyBytes) {
        this(aesKeyBytes, Clock.systemUTC());
    }

    /** Test seam: inject a fixed clock to assert the server-side age check. */
    AuthIntentCookie(byte[] aesKeyBytes, Clock clock) {
        if (aesKeyBytes.length != 32) {
            throw new IllegalArgumentException("AES-GCM intent key must be 32 bytes, got " + aesKeyBytes.length);
        }
        this.key = new SecretKeySpec(aesKeyBytes, "AES");
        this.clock = clock;
    }

    /**
     * The pre-OAuth intent — populated from /auth/login query params, consumed in the success handler.
     *
     * <p>{@code issuedAt} (epoch millis) is stamped at write time and lives inside the sealed,
     * AES-GCM-authenticated plaintext, so it cannot be forged or replayed past the TTL. The cookie
     * {@code Max-Age} is client-controlled and therefore only a hint; this field is the authoritative
     * freshness check (see {@link AuthIntentCookie#read}). An old cookie minted before this field
     * existed deserializes with {@code issuedAt == 0L} and is rejected as stale.
     */
    public record Intent(
            @Nullable String workspaceSlug,
            @Nullable String returnTo,
            Mode mode,
            @Nullable Long linkingAccountId,
            long issuedAt,
            @Nullable NativeRequest nativeRequest) {
        public enum Mode {
            /** Fresh login — JIT-create Account on first IdP subject we've never seen. */
            LOGIN,
            /** Already authenticated; attach a new IdentityLink to the current Account. */
            LINK,
            /** Fresh login for the native app: ends in a PKCE-bound handoff, never in a cookie. */
            NATIVE,
        }

        /**
         * What a native sign-in brings with it: the S256 challenge the handoff code will be bound to, the
         * app's own {@code state}, and the allowlisted redirect it returns to.
         */
        public record NativeRequest(String codeChallenge, String state, String redirectUri) {}

        @JsonCreator
        public Intent {}

        public Intent(
                @Nullable String workspaceSlug,
                @Nullable String returnTo,
                Mode mode,
                @Nullable Long linkingAccountId,
                long issuedAt) {
            this(workspaceSlug, returnTo, mode, linkingAccountId, issuedAt, null);
        }

        public static Intent login(@Nullable String workspaceSlug, @Nullable String returnTo) {
            return new Intent(workspaceSlug, returnTo, Mode.LOGIN, null, System.currentTimeMillis());
        }

        public static Intent link(@Nullable Long currentAccountId, @Nullable String returnTo) {
            return new Intent(null, returnTo, Mode.LINK, currentAccountId, System.currentTimeMillis());
        }

        public static Intent nativeLogin(NativeRequest nativeRequest) {
            return new Intent(null, null, Mode.NATIVE, null, System.currentTimeMillis(), nativeRequest);
        }
    }

    public void write(HttpServletResponse response, Intent intent) {
        try {
            byte[] plain = MAPPER.writeValueAsBytes(intent);
            byte[] sealed = encrypt(plain);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sealed);
            Cookie cookie = new Cookie(COOKIE_NAME, encoded);
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath("/");
            cookie.setMaxAge(MAX_COOKIE_AGE_SECONDS);
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        } catch (Exception ex) {
            throw new IllegalStateException("auth-intent encrypt failed", ex);
        }
    }

    @Nullable
    public Intent read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                try {
                    byte[] decoded = Base64.getUrlDecoder().decode(c.getValue());
                    byte[] plain = decrypt(decoded);
                    Intent intent = MAPPER.readValue(plain, Intent.class);
                    Instant issuedAt = Instant.ofEpochMilli(intent.issuedAt());
                    Instant now = clock.instant();
                    if (issuedAt.isAfter(now) || issuedAt.isBefore(now.minusSeconds(MAX_COOKIE_AGE_SECONDS))) {
                        log.warn("auth.oauth: rejecting stale/future auth-intent cookie");
                        return null;
                    }
                    return intent;
                } catch (RuntimeException ex) {
                    log.warn("auth.oauth: rejecting tampered/expired auth-intent cookie: {}", ex.getMessage());
                    return null;
                } catch (Exception ex) {
                    log.warn("auth.oauth: auth-intent cookie deserialize failed: {}", ex.getMessage());
                    return null;
                }
            }
        }
        return null;
    }

    public void clear(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private byte[] encrypt(byte[] plain) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RNG.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(ct, 0, out, nonce.length, ct.length);
            return out;
        } catch (Exception ex) {
            throw new IllegalStateException("auth-intent encrypt failed", ex);
        }
    }

    private byte[] decrypt(byte[] sealed) {
        if (sealed.length < NONCE_BYTES + (TAG_BITS / 8)) {
            throw new IllegalStateException("auth-intent cookie too short");
        }
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            byte[] ct = new byte[sealed.length - NONCE_BYTES];
            System.arraycopy(sealed, 0, nonce, 0, NONCE_BYTES);
            System.arraycopy(sealed, NONCE_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ct);
        } catch (Exception ex) {
            throw new IllegalStateException("auth-intent decrypt failed", ex);
        }
    }
}
