package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The JWT subject identifies the account. Provider identities come from its verified links,
 * not from display names or upstream claims copied into the session token.
 */
public interface AccountIdentityQuery {
    /** Public profile fields for a known account ID; callers enforce membership visibility. */
    Optional<AccountView> account(Long accountId);

    /** Locks the account for an access mutation; requires an enclosing transaction. */
    Optional<AccountView> accountForUpdate(Long accountId);

    /** Profiles for a known set of IDs, without one query per inventory row. */
    Map<Long, AccountView> accounts(Set<Long> accountIds);

    /** Enabled, non-team-scoped identity links; account status remains explicit in each view. */
    Map<String, AccountView> accountsForSubjects(Long providerId, Set<String> subjects);

    record AccountView(Long id, String displayName, boolean active) {}

    /** Enabled federated identities, oldest link first; empty for a missing or unlinked account. */
    List<IdentityLinkView> activeLinksForAccount(Long accountId);

    /**
     * Resolves an enabled link by immutable provider, subject and team. Account status is not checked,
     * so this also supports historical attribution; use {@link #resolveActiveAccountId} for live access.
     * The team is part of the identity key for Slack/Outline and null for GitHub/GitLab.
     */
    Optional<Long> resolveAccountId(Long providerId, String subject, @Nullable String teamId);

    /** Like {@link #resolveAccountId}, but requires an active account for admission to a new interaction. */
    Optional<Long> resolveActiveAccountId(Long providerId, String subject, @Nullable String teamId);

    /**
     * Fills an absent cached actor id. Existing references are never overwritten and are not
     * authoritative for identity resolution.
     */
    void linkExternalActor(Long identityLinkId, Long externalActorId);

    /**
     * Provider-scoped identity evidence plus the profile snapshot captured when it was linked.
     * The subject is provider-native (numeric for GitHub/GitLab, opaque for Slack/Outline).
     * Usernames and cached actor ids are metadata, not proof of actor ownership.
     */
    record IdentityLinkView(
            Long identityLinkId,
            Long gitProviderId,
            String subject,
            @Nullable String usernameAtSignup,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable String profileUrl,
            @Nullable Long externalActorId,
            @Nullable String teamId) {}
}
