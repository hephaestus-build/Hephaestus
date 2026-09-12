package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@WorkspaceAgnostic("Identity links are user-scoped (account → IdentityLink)")
public class AccountIdentityQueryService implements AccountIdentityQuery {

    private final IdentityLinkRepository identityLinkRepository;

    public AccountIdentityQueryService(IdentityLinkRepository identityLinkRepository) {
        this.identityLinkRepository = identityLinkRepository;
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
