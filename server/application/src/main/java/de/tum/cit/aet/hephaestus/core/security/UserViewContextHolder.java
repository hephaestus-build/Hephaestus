package de.tum.cit.aet.hephaestus.core.security;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatchers;

/** A verified read subject; the authentication remains the instance administrator. */
public final class UserViewContextHolder {

    public static final String WORKSPACE_HEADER = "X-User-View-Workspace";
    public static final String USER_HEADER = "X-User-View-User";
    public static final String REASON_HEADER = "X-User-View-Reason";
    public static final RequestMatcher USER_VIEW_REQUEST = RequestMatchers.anyOf(
            new RequestHeaderRequestMatcher(WORKSPACE_HEADER), new RequestHeaderRequestMatcher(USER_HEADER));
    public static final RequestMatcher USER_VIEW_READ = RequestMatchers.allOf(
            USER_VIEW_REQUEST, PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/**"));

    public record View(long workspaceId, long userId) {}

    private static final ThreadLocal<View> VIEW = new ThreadLocal<>();

    private UserViewContextHolder() {}

    public static void set(View view) {
        VIEW.set(view);
    }

    @Nullable
    public static View get() {
        return VIEW.get();
    }

    /** MUST be called in a {@code finally} so the ThreadLocal never leaks across pooled threads. */
    public static void clear() {
        VIEW.remove();
    }
}
