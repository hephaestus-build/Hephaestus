package de.tum.cit.aet.hephaestus.core.auth;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountAbsorptionContributor;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Exact human identity ownership spans workspaces")
@RequiredArgsConstructor
public class PeopleRegistryService implements PeopleRegistry {
    private final AccountRepository accounts;
    private final IdentityRepository identities;
    private final List<AccountAbsorptionContributor> contributors;
    private final IdentityKeyLock identityKeys;

    @Override
    @Transactional
    public long ensureHuman(PersonIdentity identity, String displayName) {
        if (displayName.isBlank()) throw new IllegalArgumentException("A human display name is required");
        lockIdentity(identity);
        var existing = identities.findByExactKey(identity.providerId(), identity.subject(), identity.teamKey());
        if (!existing.isEmpty()) {
            var row = existing.getFirst();
            if (row.getDisabledAt() != null) throw conflict("This identity is disabled.");
            var account = lockedOwner(row);
            if (account.getStatus() == Account.Status.DELETING || account.getStatus() == Account.Status.DELETED)
                throw conflict("This account is being erased.");
            return Objects.requireNonNull(account.getId());
        }
        var account = new Account(displayName);
        account.setStatus(Account.Status.UNCLAIMED);
        accounts.saveAndFlush(account);
        var row = new Identity();
        row.setAccount(account);
        row.setProviderId(identity.providerId());
        row.setProviderUserKey(identity.subject());
        row.setTeamKey(identity.teamKey());
        row.setLinkedVia(Identity.LinkedVia.ROSTER);
        identities.saveAndFlush(row);
        return Objects.requireNonNull(account.getId());
    }

    @Override
    @Transactional
    public long claim(PersonIdentity identity) {
        lockIdentity(identity);
        var row = identities.findByExactKey(identity.providerId(), identity.subject(), identity.teamKey()).stream()
                .findFirst()
                .orElseThrow(() -> conflict("This identity has no account to claim."));
        var account = lockedOwner(row);
        if (account.getStatus() == Account.Status.UNCLAIMED) account.setStatus(Account.Status.ACTIVE);
        if (account.getStatus() != Account.Status.ACTIVE) throw conflict("This account cannot sign in.");
        row.setDisabledAt(null);
        row.setLinkedVia(Identity.LinkedVia.SIGN_IN);
        identities.saveAndFlush(row);
        accounts.save(account);
        return Objects.requireNonNull(account.getId());
    }

    @Override
    @Transactional
    public void absorbUnclaimed(long claimedAccountId, long unclaimedAccountId) {
        if (claimedAccountId == unclaimedAccountId) return;
        var sourceIdentities = identities.findByAccount_Id(unclaimedAccountId);
        if (sourceIdentities.isEmpty()) throw conflict("The unclaimed account has no identity.");
        var sourceKeys = sourceIdentities.stream()
                .map(i -> PersonIdentity.of(i.getProviderId(), i.getProviderUserKey(), i.getTeamKey()))
                .toList();
        identityKeys.holdAndLock(sourceKeys);
        var first = accounts.findByIdForUpdate(Math.min(claimedAccountId, unclaimedAccountId))
                .orElseThrow(() -> conflict("This account is unavailable."));
        var second = accounts.findByIdForUpdate(Math.max(claimedAccountId, unclaimedAccountId))
                .orElseThrow(() -> conflict("This account is unavailable."));
        var target = claimedAccountId < unclaimedAccountId ? first : second;
        var source = claimedAccountId < unclaimedAccountId ? second : first;
        if (source.getStatus() != Account.Status.UNCLAIMED
                || (target.getStatus() != Account.Status.ACTIVE && target.getStatus() != Account.Status.SUSPENDED))
            throw conflict("Only an unclaimed account can be absorbed by its verified owner.");
        for (var contributor : contributors) contributor.beforeAbsorb(claimedAccountId, unclaimedAccountId);
        for (var contributor : contributors) contributor.absorb(claimedAccountId, unclaimedAccountId);
        accounts.flush();
        for (var contributor : contributors) contributor.afterAbsorb(claimedAccountId, unclaimedAccountId);
        for (var contributor : contributors.reversed()) contributor.finish(claimedAccountId, unclaimedAccountId);
        target.setFeedbackDeliveryEnabled(target.isFeedbackDeliveryEnabled() && source.isFeedbackDeliveryEnabled());
        // An unclaimed account never signed in, so it never answered the public activity question:
        // the claimed account's own answer, or its absence, stands.
        var sourceChoiceAt = source.getAiChoiceAt();
        var targetChoiceAt = target.getAiChoiceAt();
        if (sourceChoiceAt != null && (targetChoiceAt == null || sourceChoiceAt.isAfter(targetChoiceAt))) {
            target.setAiChoice(source.getAiChoice());
            target.setAiChoiceAt(sourceChoiceAt);
        }
        accounts.save(target);
        accounts.delete(source);
    }

    private Account lockedOwner(Identity identity) {
        long ownerId =
                identities.findOwnerId(identity.getId()).orElseThrow(() -> conflict("This account is unavailable."));
        var account = accounts.findByIdForUpdate(ownerId).orElseThrow(() -> conflict("This account is unavailable."));
        identity.setAccount(account);
        return account;
    }

    private void lockIdentity(PersonIdentity identity) {
        identityKeys.holdAndLock(List.of(identity));
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
