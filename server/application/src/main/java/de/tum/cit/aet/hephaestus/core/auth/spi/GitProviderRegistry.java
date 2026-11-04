package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Provider-owned identity lookup. Display data comes from the current provider mirror. */
public interface GitProviderRegistry {
    long resolveProviderId(String providerTypeName, String baseUrl);

    /** Returns an existing exact provider namespace without creating one. */
    Optional<Long> findProviderId(String providerTypeName, String baseUrl);

    String providerTypeName(@Nullable Long gitProviderId);

    @Nullable
    String providerServerUrl(@Nullable Long gitProviderId);

    Optional<Long> findActorId(long gitProviderId, String subject);

    Optional<ActorProfile> actor(long actorId);

    /** The human actors behind the account's enabled git provider links, oldest link first, in one read. */
    List<ActorProfile> accountActors(long accountId);

    record ActorProfile(
            long actorId,
            long providerId,
            String providerType,
            String serverUrl,
            String providerUserKey,
            String login,
            String displayName,
            boolean human) {}

    boolean visibleInWorkspace(long providerId, long workspaceId);

    Map<IdentityKey, IdentityProfile> profiles(Collection<IdentityKey> identities);

    record IdentityKey(long providerId, String providerUserKey, String teamKey) {}

    record IdentityProfile(
            @Nullable String login,
            @Nullable String displayName,
            @Nullable String avatarUrl,
            @Nullable String profileUrl) {}
}
