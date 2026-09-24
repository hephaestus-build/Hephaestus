package de.tum.cit.aet.hephaestus.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Hides locally invalid access cookies from authentication so public endpoints remain usable.
 * Does not write cookies: a delayed anonymous response must not erase a newer sign-in.
 * Protected endpoints still reject the resulting anonymous request.
 */
public class StaleAuthCookieFilter extends OncePerRequestFilter {

    /** Request attribute set when the access cookie failed local validation; read by the resolver. */
    public static final String COOKIE_INVALID_ATTRIBUTE = StaleAuthCookieFilter.class.getName() + ".COOKIE_INVALID";

    private final String cookieName;
    private final JwtDecoder localDecoder;

    public StaleAuthCookieFilter(String cookieName, JwtDecoder localDecoder) {
        this.cookieName = cookieName;
        this.localDecoder = localDecoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = readAuthCookie(request);
        if (token != null) {
            try {
                localDecoder.decode(token);
            } catch (JwtException invalid) {
                request.setAttribute(COOKIE_INVALID_ATTRIBUTE, Boolean.TRUE);
            }
        }
        chain.doFilter(request, response);
    }

    private @Nullable String readAuthCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value != null && !value.isBlank()) ? value : null;
            }
        }
        return null;
    }
}
