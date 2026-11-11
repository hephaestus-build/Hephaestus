package de.tum.cit.aet.hephaestus.integration.core.oauth;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Binds a connect flow to the browser that started it (RFC 9700 §4.7.1, ASVS 5.0 10.1.2). The connect request sets a
 * host-only cookie that holds a hash of the flow's state; the callback accepts the state only with that cookie. A
 * signed state alone proves who started the flow, not which browser returns with it: a link to a started flow,
 * opened in another browser, would otherwise connect the other person's provider account to the starter's workspace.
 *
 * <p>The API and the callback share the apex host (ADR 0053), and the provider's redirect to the callback is a
 * top-level navigation, so a {@code SameSite=Lax} cookie reaches it. Each flow has its own cookie, so two connects in
 * one browser do not replace each other. Like the sign-in state cookie, it is always {@code Secure}; browsers accept
 * that on {@code localhost}.
 */
@Component
@ConditionalOnServerRole
public class OAuthBrowserBinding {

    private static final String COOKIE_PREFIX = "__Host-CONNECT_";
    private static final int NAME_HASH_LENGTH = 16;

    private final int maxAgeSeconds;

    public OAuthBrowserBinding(OAuthStateProperties properties) {
        this.maxAgeSeconds = Math.toIntExact(properties.ttl().toSeconds());
    }

    /** Marks this browser as the one that started the flow of {@code state}. */
    public void bind(HttpServletResponse response, String state) {
        response.addCookie(cookie(cookieName(state), cookieValue(state), maxAgeSeconds));
    }

    /** Whether the request comes from the browser that started the flow of {@code state}. */
    public static boolean startedHere(HttpServletRequest request, String state) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        String name = cookieName(state);
        byte[] expected = cookieValue(state).getBytes(StandardCharsets.US_ASCII);
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())
                    && MessageDigest.isEqual(expected, cookie.getValue().getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** Ends the binding: a state is used once, whatever the callback's outcome. */
    public static void release(HttpServletResponse response, String state) {
        response.addCookie(cookie(cookieName(state), "", 0));
    }

    /** The cookie that the browser which started the flow of {@code state} sends back. */
    public static String cookieName(String state) {
        return COOKIE_PREFIX + sha256(state).substring(0, NAME_HASH_LENGTH);
    }

    /** The value of {@link #cookieName(String)}: the state's hash, never the state itself. */
    public static String cookieValue(String state) {
        return sha256(state);
    }

    private static Cookie cookie(String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }

    private static String sha256(String state) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
