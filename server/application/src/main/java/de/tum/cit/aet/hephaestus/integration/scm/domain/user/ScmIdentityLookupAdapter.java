package de.tum.cit.aet.hephaestus.integration.scm.domain.user;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ScmIdentityLookup;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ScmIdentityLookupAdapter implements ScmIdentityLookup {
    private final UserRepository users;

    @Override
    public Optional<ActorProfile> actor(long actorId) {
        return users.findById(actorId)
                .map(user -> new ActorProfile(
                        user.getId(),
                        Objects.requireNonNull(user.getProvider().getId(), "Persisted SCM provider requires an id"),
                        user.getProvider().getType().name(),
                        user.getProvider().getServerUrl(),
                        Long.toString(user.getNativeId()),
                        user.getLogin(),
                        user.getName() == null ? user.getLogin() : user.getName(),
                        user.getType() == User.Type.USER));
    }

    @Override
    public List<ActorProfile> accountActors(long accountId) {
        return users.findAccountActors(accountId);
    }

    @Override
    public List<UserProfile> profiles(Collection<Long> providerIds, Collection<Long> nativeIds) {
        return users.findIdentityProfiles(providerIds, nativeIds).stream()
                .map(user -> new UserProfile(
                        user.getProviderId(),
                        user.getNativeId(),
                        user.getLogin(),
                        user.getDisplayName(),
                        user.getAvatarUrl(),
                        user.getProfileUrl()))
                .toList();
    }
}
