package de.tum.cit.aet.hephaestus.core.security;

import org.jspecify.annotations.Nullable;

/** A verified read subject; the authentication remains the instance administrator. */
public final class UserViewContextHolder {

    public record View(long workspaceId, String workspaceSlug, long userId, String login) {}

    private static final ThreadLocal<View> VIEW = new ThreadLocal<>();

    private UserViewContextHolder() {}

    public static void set(View view) {
        VIEW.set(view);
    }

    @Nullable
    public static View get() {
        return VIEW.get();
    }

    public static void clear() {
        VIEW.remove();
    }
}
