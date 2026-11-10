package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Capture reads review eligibility and selection without the server-only roster bean. */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class MembershipReviewSelectionService implements MembershipReviewSelection {
    private final MembershipRepository memberships;
    private final WorkspaceActorSelector actors;
    private final PersonProcessingSuppression suppression;

    @Override
    @Transactional(readOnly = true)
    public Set<Long> eligibleAccountIds(long workspaceId) {
        return eligible(workspaceId, List.of(), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> eligibleAccountIds(long workspaceId, Collection<Long> accountIds) {
        return accountIds.isEmpty() ? Set.of() : eligible(workspaceId, accountIds, false);
    }

    private Set<Long> eligible(long workspaceId, Collection<Long> accountIds, boolean allAccounts) {
        var eligible = memberships.findEligibleAccountIds(
                workspaceId,
                accountIds,
                allAccounts,
                actors.connectedProviderId(workspaceId).orElse(null));
        var suppressed = suppression.suppressedAccountIds(eligible);
        return eligible.stream().filter(id -> !suppressed.contains(id)).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> selectedAccountIds(long workspaceId) {
        return memberships.findSelectedAccountIds(workspaceId);
    }

    @Override
    @Transactional
    public void replaceSelection(long workspaceId, Collection<Long> accountIds) {
        var selected = Set.copyOf(accountIds);
        memberships
                .findReviewSelectionMembers(workspaceId, selected)
                .forEach(member -> member.setReviewSelected(selected.contains(member.getAccountId())));
    }
}
