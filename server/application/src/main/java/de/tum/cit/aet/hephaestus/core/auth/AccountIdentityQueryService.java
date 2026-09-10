package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("Identity links are user-scoped (account → IdentityLink)")
public class AccountIdentityQueryService implements AccountIdentityQuery {

    private final IdentityLinkRepository identityLinkRepository;
    private final AccountRepository accountRepository;

    public AccountIdentityQueryService(
            IdentityLinkRepository identityLinkRepository, AccountRepository accountRepository) {
        this.identityLinkRepository = identityLinkRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> account(Long accountId) {
        return accountRepository
                .findById(accountId)
                .map(account -> new AccountView(
                        Objects.requireNonNull(account.getId()),
                        account.getDisplayName(),
                        account.getStatus() == Account.Status.ACTIVE));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<AccountView> accountForUpdate(Long accountId) {
        return accountRepository
                .findByIdForUpdate(accountId)
                .map(account -> new AccountView(
                        Objects.requireNonNull(account.getId()),
                        account.getDisplayName(),
                        account.getStatus() == Account.Status.ACTIVE));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, AccountView> accounts(Set<Long> accountIds) {
        Map<Long, AccountView> result = new HashMap<>();
        List<Long> ids = List.copyOf(accountIds);
        for (int from = 0; from < ids.size(); from += 1000) {
            accountRepository
                    .findAllById(ids.subList(from, Math.min(from + 1000, ids.size())))
                    .forEach(account -> result.put(Objects.requireNonNull(account.getId()), toAccountView(account)));
        }
        return Map.copyOf(result);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, AccountView> accountsForSubjects(Long providerId, Set<String> subjects) {
        Map<String, AccountView> result = new HashMap<>();
        List<String> keys = List.copyOf(subjects);
        // Directory captures can exceed PostgreSQL's bind-parameter limit; bound every IN query.
        for (int from = 0; from < keys.size(); from += 1000) {
            identityLinkRepository
                    .findActiveByProviderSubjects(providerId, keys.subList(from, Math.min(from + 1000, keys.size())))
                    .forEach(link -> result.put(link.getSubject(), toAccountView(link.getAccount())));
        }
        return Map.copyOf(result);
    }

    private static AccountView toAccountView(Account account) {
        return new AccountView(
                Objects.requireNonNull(account.getId()),
                account.getDisplayName(),
                account.getStatus() == Account.Status.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityLinkView> activeLinksForAccount(Long accountId) {
        if (accountId == null) {
            return List.of();
        }
        return identityLinkRepository.findActiveByAccountId(accountId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> resolveAccountId(Long providerId, String subject, @Nullable String teamId) {
        if (providerId == null || subject == null || subject.isBlank()) {
            return Optional.empty();
        }
        return identityLinkRepository
                .findActiveByProviderSubject(providerId, subject, teamId)
                .map(link -> link.getAccount().getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> resolveActiveAccountId(Long providerId, String subject, @Nullable String teamId) {
        if (subject.isBlank()) {
            return Optional.empty();
        }
        return identityLinkRepository
                .findActiveByProviderSubject(providerId, subject, teamId)
                .map(IdentityLink::getAccount)
                .filter(account -> account.getStatus() == Account.Status.ACTIVE)
                .map(Account::getId);
    }

    @Override
    @Transactional
    public void linkExternalActor(Long identityLinkId, Long externalActorId) {
        if (identityLinkId == null || externalActorId == null) {
            return;
        }
        identityLinkRepository.linkExternalActorIfAbsent(identityLinkId, externalActorId);
    }

    private IdentityLinkView toView(IdentityLink link) {
        return new IdentityLinkView(
                link.getId(),
                link.getProviderId(),
                link.getSubject(),
                link.getUsernameAtSignup(),
                link.getDisplayName(),
                link.getAvatarUrl(),
                link.getProfileUrl(),
                link.getExternalActorId(),
                link.getTeamId());
    }
}
