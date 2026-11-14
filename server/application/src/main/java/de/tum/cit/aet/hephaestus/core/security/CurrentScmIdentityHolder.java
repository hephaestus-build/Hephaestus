package de.tum.cit.aet.hephaestus.core.security;

import java.util.Optional;
import java.util.Set;

/**
 * Verified SCM actor for a workspace request or conversation turn. The actor id is the lookup key. The account's actor ids are every actor of the same account
 * that is a member of the workspace, the selected one included, so what the account created
 * through another of them stays its own when the selection changes.
 */
public final class CurrentScmIdentityHolder {

    private record Identity(long userId, Set<Long> accountActorIds) {}

    private static final ThreadLocal<Identity> IDENTITY = new ThreadLocal<>();

    private CurrentScmIdentityHolder() {}

    public static void set(long userId, Set<Long> accountActorIds) {
        if (!accountActorIds.contains(userId)) {
            throw new IllegalArgumentException("The selected actor must be one of the account's actors");
        }
        IDENTITY.set(new Identity(userId, Set.copyOf(accountActorIds)));
    }

    public static Optional<Long> getUserId() {
        return Optional.ofNullable(IDENTITY.get()).map(Identity::userId);
    }

    /** Empty when no actor is pinned. */
    public static Set<Long> getAccountActorIds() {
        return Optional.ofNullable(IDENTITY.get())
                .map(Identity::accountActorIds)
                .orElse(Set.of());
    }

    /** MUST be called in a {@code finally} so the ThreadLocal never leaks across pooled threads. */
    public static void clear() {
        IDENTITY.remove();
    }
}
