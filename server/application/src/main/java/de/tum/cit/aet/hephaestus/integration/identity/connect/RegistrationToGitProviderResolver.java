package de.tum.cit.aet.hephaestus.integration.identity.connect;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.connection.SourceProviderNamespaces;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionQueries;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RegistrationToGitProviderResolver implements GitProviderRegistry {

    private static final String UNKNOWN = "UNKNOWN";

    private final IdentityProviderLookup providers;
    private final UserRepository userRepository;
    private final ConnectionQueries connections;
    private final SourceProviderNamespaces namespaces;

    public RegistrationToGitProviderResolver(
            IdentityProviderLookup providers,
            UserRepository userRepository,
            ConnectionQueries connections,
            SourceProviderNamespaces namespaces) {
        this.providers = providers;
        this.userRepository = userRepository;
        this.connections = connections;
        this.namespaces = namespaces;
    }

    /**
     * Get-or-create the {@code identity_provider} row, committing in its OWN transaction ({@link
     * Propagation#REQUIRES_NEW}). This is required for correctness, not just isolation: account
     * provisioning inserts the {@code identity} (FK {@code sfk_identity_provider}) inside {@code
     * AccountJitCreator}'s {@code REQUIRES_NEW} transaction, which under READ_COMMITTED cannot see
     * an uncommitted {@code identity_provider} row. The first login on a not-yet-seen instance
     * (e.g. a self-hosted gitlab.lrz.de) would otherwise create the row in the outer login
     * transaction and then fail the FK from the inner JIT transaction. The provider row is
     * idempotent reference data (an SCM instance registration, reused across logins — exactly like
     * the env-seeded github.com / gitlab.com rows), so committing it independently of the login
     * outcome is correct.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long resolveProviderId(String providerTypeName, String baseUrl) {
        IdentityProviderType type = IdentityProviderType.valueOf(providerTypeName);
        String origin = ScmOrigin.of(baseUrl)
                .orElseThrow(() -> new IllegalStateException("login provider baseUrl has no origin: " + baseUrl));
        return providers
                .findByOrigin(type, origin)
                .map(IdentityProviderLookup.Provider::id)
                .orElseGet(() -> namespaces.registerIdentityProvider(type, origin));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> findProviderId(String providerTypeName, String baseUrl) {
        var type = IdentityProviderType.valueOf(providerTypeName);
        return ScmOrigin.of(baseUrl)
                .flatMap(origin -> providers.findByOrigin(type, origin))
                .map(IdentityProviderLookup.Provider::id);
    }

    @Override
    @Transactional(readOnly = true)
    public String providerTypeName(@Nullable Long gitProviderId) {
        if (gitProviderId == null) {
            return UNKNOWN;
        }
        return providers.findById(gitProviderId).map(p -> p.type().name()).orElse(UNKNOWN);
    }

    @Override
    @Transactional(readOnly = true)
    public @Nullable String providerServerUrl(@Nullable Long gitProviderId) {
        if (gitProviderId == null) {
            return null;
        }
        return providers
                .findById(gitProviderId)
                .map(provider -> ScmOrigin.of(provider.serverUrl()).orElse(provider.serverUrl()))
                .orElse(null);
    }

    private static Optional<Long> nativeId(String subject) {
        try {
            return Optional.of(Long.parseLong(subject));
        } catch (NumberFormatException notAGitSubject) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<IdentityKey, IdentityProfile> profiles(Collection<IdentityKey> identities) {
        var requested = new HashSet<IdentityKey>();
        var providerIds = new HashSet<Long>();
        var nativeIds = new HashSet<Long>();
        for (var identity : identities) {
            if (!identity.teamKey().isEmpty()) continue;
            nativeId(identity.providerUserKey())
                    .filter(id -> identity.providerUserKey().equals(id.toString()))
                    .ifPresent(id -> {
                        requested.add(identity);
                        providerIds.add(identity.providerId());
                        nativeIds.add(id);
                    });
        }
        if (requested.isEmpty()) return Map.of();
        var result = new HashMap<IdentityKey, IdentityProfile>();
        for (var user : userRepository.findIdentityProfiles(providerIds, nativeIds)) {
            var key = new IdentityKey(user.getProviderId(), Long.toString(user.getNativeId()), "");
            if (requested.contains(key))
                result.put(
                        key,
                        new IdentityProfile(
                                user.getLogin(), user.getDisplayName(), user.getAvatarUrl(), user.getProfileUrl()));
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean visibleInWorkspace(long providerId, long workspaceId) {
        var provider = providers.findById(providerId).orElse(null);
        if (provider == null) return false;
        var origin = ScmOrigin.of(provider.serverUrl());
        if (origin.isEmpty()) return false;
        return connections.hasActiveSourceInstance(workspaceId, provider.kind(), origin.get());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActorProfile> accountActors(long accountId) {
        return userRepository.findAccountActors(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ActorProfile> actor(long actorId) {
        return userRepository
                .findById(actorId)
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
}
