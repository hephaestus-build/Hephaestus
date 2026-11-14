package de.tum.cit.aet.hephaestus.integration.scm.spi;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The SCM user mirror, as identity resolution outside the SCM module reads it. */
public interface ScmIdentityLookup {
    Optional<ActorProfile> actor(long actorId);

    /** The human actors behind the account's enabled git provider links, oldest link first, in one read. */
    List<ActorProfile> accountActors(long accountId);

    /** The mirrored users whose provider is one of {@code providerIds} and whose native id is one of {@code nativeIds}. */
    List<UserProfile> profiles(Collection<Long> providerIds, Collection<Long> nativeIds);

    record UserProfile(
            long providerId,
            long nativeId,
            String login,
            @Nullable String displayName,
            String avatarUrl,
            String profileUrl) {}
}
