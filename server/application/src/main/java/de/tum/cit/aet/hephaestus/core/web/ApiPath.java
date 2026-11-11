package de.tum.cit.aet.hephaestus.core.web;

import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.util.UrlPathHelper;

/**
 * Where the API lives on the origin. The server serves every application route under {@link #PREFIX}, the same path
 * the browser, the extension and the OpenAPI {@code servers} entry use, so no proxy rewrites a path. Provider webhooks
 * ({@code /webhooks/**}) and the sandbox gateway ({@code /internal/**}) keep their own roots.
 */
public final class ApiPath {

    public static final String PREFIX = "/api";

    private ApiPath() {}

    /** The public path of an API route given relative to the API, such as {@code /user}. */
    public static String of(String path) {
        return PREFIX + path;
    }

    /** A path relative to the API, such as {@code /user} for {@code /api/user}; null outside the API. */
    public static @Nullable String relative(String path) {
        return path.startsWith(PREFIX + "/") ? path.substring(PREFIX.length()) : null;
    }

    /**
     * The request's path relative to the API, decoded as handler mapping and Spring Security match it, so
     * {@code /%61pi/user} is {@code /user} here as it is there; null outside the API.
     */
    public static @Nullable String relative(HttpServletRequest request) {
        return relative(UrlPathHelper.defaultInstance.getPathWithinApplication(request));
    }

    /** Request matchers whose patterns are given relative to the API, such as {@code /user/**}. */
    public static PathPatternRequestMatcher.Builder matchers() {
        return PathPatternRequestMatcher.withDefaults().basePath(PREFIX);
    }
}
