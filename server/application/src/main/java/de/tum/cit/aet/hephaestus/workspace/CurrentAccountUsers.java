package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves account-owned SCM actors by verified provider subject. Usernames can be reassigned and
 * cached actor references can become stale, so neither is identity evidence.
 */
@Component
@WorkspaceAgnostic("Resolves the principal's SCM user mirrors; not scoped to a single workspace")
public class CurrentAccountUsers {

    private final AccountIdentityQuery accountIdentityQuery;
    private final GitProviderRegistry providers;

    public CurrentAccountUsers(AccountIdentityQuery accountIdentityQuery, GitProviderRegistry providers) {
        this.accountIdentityQuery = accountIdentityQuery;
        this.providers = providers;
    }

    /** Resolve the authenticated account; absent or unlinked accounts own no SCM actors. */
    @Transactional(readOnly = true)
    public List<ActorProfile> resolve() {
        return SecurityUtils.getCurrentAccountId()
                .map(this::resolveAccountUsers)
                .orElseGet(List::of);
    }

    /**
     * Resolve one account without relying on the request's authentication (exports and provider
     * events).
     */
    @Transactional(readOnly = true)
    public List<ActorProfile> resolve(Long accountId) {
        return resolveAccountUsers(accountId);
    }

    /**
     * The server URL of each active link that the authenticated account holds on a git provider of this
     * type. A link needs no synced actor.
     */
    @Transactional(readOnly = true)
    public List<String> providerLinks(String providerType) {
        return SecurityUtils.getCurrentAccountId()
                .map(accountId -> accountIdentityQuery.activeLinksForAccount(accountId).stream()
                        .filter(link -> link.teamKey().isEmpty()
                                && providerType.equals(providers.providerTypeName(link.providerId())))
                        .map(link -> providers.providerServerUrl(link.providerId()))
                        .filter(Objects::nonNull)
                        .toList())
                .orElseGet(List::of);
    }

    private List<ActorProfile> resolveAccountUsers(Long accountId) {
        // Preserve linking order: adding another identity must not change an existing
        // representative actor.
        Map<Long, ActorProfile> byId = new LinkedHashMap<>();
        for (AccountIdentityQuery.IdentityView link : accountIdentityQuery.activeLinksForAccount(accountId)) {
            resolveLinkUser(link).ifPresent(user -> byId.putIfAbsent(user.actorId(), user));
        }
        return List.copyOf(byId.values());
    }

    private Optional<ActorProfile> resolveLinkUser(AccountIdentityQuery.IdentityView link) {
        if (!link.teamKey().isEmpty()) return Optional.empty();
        return providers
                .findActorId(link.providerId(), link.providerUserKey())
                .flatMap(providers::actor)
                .filter(ActorProfile::human);
    }
}
