package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parameter object for looking up or creating a GitLab user from GraphQL data.
 * <p>
 * Wraps the identity + profile fields needed to persist a user row so that
 * {@code findOrCreateUser} can take a single payload instead of a long
 * parameter list. {@code publicEmail} is optional because only some callers
 * have access to the {@code GitLabUserFields} GraphQL fragment. {@code bot} is GitLab's own statement of whether the
 * account is a bot; {@code null} when the read did not carry it, which says neither.
 */
public record GitLabUserLookup(
        @Nullable String globalId,
        @Nullable String username,
        @Nullable String name,
        @Nullable String avatarUrl,
        @Nullable String webUrl,
        @Nullable String publicEmail,
        @Nullable Boolean bot) {
    /** Convenience factory for callers that do not resolve {@code publicEmail}. */
    public static GitLabUserLookup of(
            @Nullable String globalId,
            @Nullable String username,
            @Nullable String name,
            @Nullable String avatarUrl,
            @Nullable String webUrl,
            @Nullable Boolean bot) {
        return new GitLabUserLookup(globalId, username, name, avatarUrl, webUrl, null, bot);
    }

    /** GitLab's {@code bot} flag in a user object of a response, or {@code null} when absent or unreadable. */
    public static @Nullable Boolean botOf(@Nullable Map<?, ?> user) {
        return (user != null && user.get("bot") instanceof Boolean bot) ? bot : null;
    }
}
