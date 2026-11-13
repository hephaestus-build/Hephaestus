package de.tum.cit.aet.hephaestus.core.auth.oauth;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the in-flight {@link OAuth2AuthorizationRequest} in an AES-GCM-encrypted cookie
 * rather than the HTTP session. Required because every existing Hephaestus filter chain
 * is {@code STATELESS} — the Spring default
 * {@code HttpSessionOAuth2AuthorizationRequestRepository} would silently create a session
 * that the callback request cannot see on another pod.
 *
 * <h2>Cookie</h2>
 * Name {@value #COOKIE_NAME}; {@code HttpOnly}, {@code Secure}, {@code SameSite=Lax}
 * ({@code Strict} would break the cross-site IdP callback), {@code Path=/}, 10-minute TTL.
 *
 * <h2>Serialization</h2>
 * The {@link OAuth2AuthorizationRequest} is projected to a constrained JSON shape
 * ({@link StoredAuthorizationRequest}), NOT Java native serialization. Even though the bytes are
 * AES-GCM-sealed, {@code ObjectInputStream} on a value that round-trips through a client is the
 * pattern OWASP says to eliminate, and the {@code Serializable} contract is not stable across
 * Spring Security upgrades. The PKCE {@code code_verifier} and the {@code registration_id} live in
 * {@code getAttributes()} and MUST survive the round-trip or the token-exchange leg fails.
 *
 * <h2>Encryption</h2>
 * AES-256-GCM. Nonce is the first 12 bytes; ciphertext+tag follow. AAD is the literal
 * {@code "oauth2-state"} — distinct from any tenant or system AAD so a confused-deputy
 * substitution between domains is rejected.
 *
 * <p>One authorization request is stored per browser; starting another replaces it.
 */
public class CookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String COOKIE_NAME = "__Host-OAUTH_STATE";
    private static final String AAD = "oauth2-state";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_COOKIE_AGE_SECONDS = 600; // 10 minutes
    private static final Logger log = LoggerFactory.getLogger(CookieOAuth2AuthorizationRequestRepository.class);
    private static final SecureRandom RNG = new SecureRandom();
    private static final JsonMapper MAPPER = new JsonMapper();

    /**
     * Constrained JSON projection of {@link OAuth2AuthorizationRequest}. Replaces Java native
     * serialization — JSON is the version-stable, gadget-chain-free encoding for a value that
     * round-trips through a client (OWASP). Only the authorization-code grant is ever stored (login
     * always uses {@code authorizationCode()}), so the grant is carried but assumed AUTHORIZATION_CODE
     * on rebuild. {@code attributes} MUST survive intact — the PKCE {@code code_verifier} and the
     * {@code registration_id} live there.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StoredAuthorizationRequest(
            String authorizationUri,
            String clientId,
            String redirectUri,
            Set<String> scopes,
            String state,
            String authorizationRequestUri,
            Map<String, String> attributes,
            Map<String, String> additionalParameters) {}

    private final SecretKeySpec key;

    public CookieOAuth2AuthorizationRequestRepository(byte[] aesKeyBytes) {
        if (aesKeyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "AES-GCM cookie key must be 32 bytes (256-bit), got " + aesKeyBytes.length);
        }
        this.key = new SecretKeySpec(aesKeyBytes, "AES");
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        Cookie cookie = findCookie(request);
        if (cookie == null) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cookie.getValue());
            byte[] plain = decrypt(decoded);
            return deserialize(plain);
        } catch (RuntimeException ex) {
            log.warn(
                    "auth.oauth: rejecting tampered/expired oauth-state cookie: errorType={}",
                    ex.getClass().getName());
            return null;
        }
    }

    @Override
    public void saveAuthorizationRequest(
            @Nullable OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (authorizationRequest == null) {
            clear(response);
            return;
        }
        byte[] plain = serialize(authorizationRequest);
        byte[] sealed = encrypt(plain);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(sealed);
        Cookie cookie = new Cookie(COOKIE_NAME, encoded);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(MAX_COOKIE_AGE_SECONDS);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    @Override
    public @Nullable OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest loaded = loadAuthorizationRequest(request);
        if (loaded != null) {
            clear(response);
        }
        return loaded;
    }

    private void clear(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private @Nullable Cookie findCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c;
            }
        }
        return null;
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
            throw new IllegalStateException("oauth-state encrypt failed", ex);
        }
    }

    private byte[] decrypt(byte[] sealed) {
        if (sealed.length < NONCE_BYTES + (TAG_BITS / 8)) {
            throw new IllegalStateException("oauth-state cookie too short");
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
            throw new IllegalStateException("oauth-state decrypt failed", ex);
        }
    }

    private static byte[] serialize(OAuth2AuthorizationRequest req) {
        // PKCE stores code_verifier + registration_id as String attributes; the OAuth2 login flow never
        // puts non-String values in attributes/additionalParameters, so a String projection is lossless.
        StoredAuthorizationRequest stored = new StoredAuthorizationRequest(
                req.getAuthorizationUri(),
                req.getClientId(),
                Objects.requireNonNull(req.getRedirectUri(), "OAuth redirect URI must be configured"),
                req.getScopes(),
                Objects.requireNonNull(req.getState(), "OAuth state must be generated before serialization"),
                req.getAuthorizationRequestUri(),
                stringifyValues(req.getAttributes()),
                stringifyValues(req.getAdditionalParameters()));
        try {
            return MAPPER.writeValueAsBytes(stored);
        } catch (Exception ex) {
            throw new IllegalStateException("oauth-state serialize failed", ex);
        }
    }

    private static OAuth2AuthorizationRequest deserialize(byte[] bytes) {
        StoredAuthorizationRequest stored;
        try {
            stored = MAPPER.readValue(bytes, StoredAuthorizationRequest.class);
        } catch (Exception ex) {
            throw new IllegalStateException("oauth-state deserialize failed", ex);
        }
        // Login is always the authorization-code grant (AuthSecurityConfig wires authorizationCode()).
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(stored.authorizationUri())
                .clientId(stored.clientId())
                .redirectUri(stored.redirectUri())
                .state(stored.state())
                .authorizationRequestUri(stored.authorizationRequestUri());
        if (stored.scopes() != null) {
            builder.scopes(stored.scopes());
        }
        if (stored.attributes() != null) {
            // Carries the PKCE code_verifier + registration_id — MUST be restored or token exchange fails.
            builder.attributes(attrs -> attrs.putAll(stored.attributes()));
        }
        if (stored.additionalParameters() != null) {
            builder.additionalParameters(params -> params.putAll(stored.additionalParameters()));
        }
        return builder.build();
    }

    private static Map<String, String> stringifyValues(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        source.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v.toString());
            }
        });
        return out;
    }
}
