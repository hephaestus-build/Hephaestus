package de.tum.cit.aet.hephaestus.core.security;

import java.util.Optional;

/**
 * Verified SCM actor for a workspace request or mentor turn. The actor id is the lookup key;
 * the login is retained for display.
 */
public final class CurrentScmIdentityHolder {

    private record Identity(long userId, String login) {}

    private static final ThreadLocal<Identity> IDENTITY = new ThreadLocal<>();

    private CurrentScmIdentityHolder() {}

    public static void set(long userId, String login) {
        IDENTITY.set(new Identity(userId, login));
    }

    public static Optional<Long> getUserId() {
        return Optional.ofNullable(IDENTITY.get()).map(Identity::userId);
    }

    public static Optional<String> getLogin() {
        return Optional.ofNullable(IDENTITY.get()).map(Identity::login);
    }

    /** MUST be called in a {@code finally} so the ThreadLocal never leaks across pooled threads. */
    public static void clear() {
        IDENTITY.remove();
    }
}
