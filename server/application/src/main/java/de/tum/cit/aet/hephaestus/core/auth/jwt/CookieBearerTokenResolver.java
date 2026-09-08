package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.security.StaleAuthCookieFilter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Resolves the configured access-token cookie before falling back to a standard bearer header.
 * Rejected stale cookies are ignored. See ADR 0017 for the cookie-first security policy.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private static final RequestMatcher PUBLIC_DISCOVERY =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/identity-providers");

    private final String cookieName;
    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    public CookieBearerTokenResolver(AuthProperties properties) {
        this.cookieName = properties.cookieName();
    }

    @Override
    public @Nullable String resolve(HttpServletRequest request) {
        // Discovery never uses identity; revoked credentials must not block signing in again.
        if (PUBLIC_DISCOVERY.matches(request)) {
            return null;
        }
        // A rejected stale cookie must not authenticate this request.
        if (Boolean.TRUE.equals(request.getAttribute(StaleAuthCookieFilter.COOKIE_INVALID_ATTRIBUTE))) {
            return headerResolver.resolve(request);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && !value.isBlank()) {
                        return value;
                    }
                }
            }
        }
        return headerResolver.resolve(request);
    }
}
