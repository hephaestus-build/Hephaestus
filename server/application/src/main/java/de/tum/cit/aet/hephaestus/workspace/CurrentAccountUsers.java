package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves account-owned SCM actors by verified provider subject. Usernames can be reassigned
 * and cached actor references can become stale, so neither is identity evidence.
 */
@Component
@WorkspaceAgnostic("Resolves the principal's SCM user mirrors; not scoped to a single workspace")
public class CurrentAccountUsers {

    private final AccountIdentityQuery accountIdentityQuery;
    private final UserRepository userRepository;

    public CurrentAccountUsers(AccountIdentityQuery accountIdentityQuery, UserRepository userRepository) {
        this.accountIdentityQuery = accountIdentityQuery;
        this.userRepository = userRepository;
    }

    /** Resolve the authenticated account; absent or unlinked accounts own no SCM actors. */
    @Transactional(readOnly = true)
    public List<User> resolve() {
        return SecurityUtils.getCurrentAccountId()
                .map(this::resolveAccountUsers)
                .orElseGet(List::of);
    }

    /** Resolve one account without relying on the request's authentication (exports and provider events). */
    @Transactional(readOnly = true)
    public List<User> resolve(Long accountId) {
        return resolveAccountUsers(accountId);
    }

    private List<User> resolveAccountUsers(Long accountId) {
        // Preserve linking order: adding another identity must not change an existing representative actor.
        Map<Long, User> byId = new LinkedHashMap<>();
        for (AccountIdentityQuery.IdentityLinkView link : accountIdentityQuery.activeLinksForAccount(accountId)) {
            resolveLinkUser(link)
                    .filter(user -> user.getId() != null)
                    .ifPresent(user -> byId.putIfAbsent(user.getId(), user));
        }
        return List.copyOf(byId.values());
    }

    private Optional<User> resolveLinkUser(AccountIdentityQuery.IdentityLinkView link) {
        final long nativeId;
        try {
            nativeId = Long.parseLong(link.subject());
        } catch (NumberFormatException exception) {
            // Non-SCM identities (for example Slack and Outline) do not have numeric SCM subjects.
            return Optional.empty();
        }
        if (nativeId <= 0) {
            return Optional.empty();
        }
        return userRepository.findByNativeIdAndProviderId(nativeId, link.gitProviderId());
    }
}
