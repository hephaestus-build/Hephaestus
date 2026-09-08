package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.DirectoryIdentitySourceQuery.Source;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyDTO.Change;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyDTO.DirectoryEvidenceDTO;
import de.tum.cit.aet.hephaestus.workspace.directory.DirectoryPolicyDTO.DirectoryMemberDTO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class DirectoryPolicyViewService {
    private final DirectoryPolicyService policies;
    private final DirectoryIdentitySourceQuery sources;
    private final AccountIdentityQuery identities;
    private final WorkspaceAccountMembershipRepository memberships;
    private final ConnectionRepository connections;

    @Transactional(readOnly = true)
    public Optional<DirectoryPolicyDTO> view(long workspaceId) {
        return policies.inspect(workspaceId).map(this::view);
    }

    private DirectoryPolicyDTO view(DirectoryPolicy policy) {
        Source source = sources.approvedSource(policy.getRegistrationId()).orElse(null);
        List<String> blockers = new ArrayList<>();
        boolean connected = connections
                .findByIdAndWorkspaceId(
                        policy.getConnectionId(), policy.getWorkspace().getId())
                .filter(connection -> connection.getState() == IntegrationState.ACTIVE)
                .isPresent();
        if (source == null)
            blockers.add(
                    "An instance administrator must restore approval of this exact organizational issuer and its groups.");
        if (!connected) blockers.add("The workspace owner must reconnect the read-only directory credentials.");
        if (policy.getHealth() != DirectoryPolicy.Health.HEALTHY)
            blockers.add("An administrator must run a complete directory read before new access can be granted.");
        if (policy.getStatus() == DirectoryPolicy.Status.DRAFT)
            blockers.add("The workspace owner must preview and approve the policy before it grants access.");
        if (policy.getStatus() == DirectoryPolicy.Status.PAUSED)
            blockers.add("New access is paused. Confirmed departures continue to remove directory-managed access.");
        if (policy.getStatus() == DirectoryPolicy.Status.ENDED)
            blockers.add("Directory management has ended. Configure and approve a new policy to resume it.");
        var current = memberships.findByWorkspace_Id(policy.getWorkspace().getId());
        Set<String> subjects = new HashSet<>();
        if (policy.getActiveSnapshot() != null)
            subjects.addAll(policy.getActiveSnapshot().eligibleSubjects().keySet());
        if (policy.getPreviewSnapshot() != null)
            subjects.addAll(policy.getPreviewSnapshot().eligibleSubjects().keySet());
        current.stream()
                .map(WorkspaceAccountMembership::getDirectorySubject)
                .filter(java.util.Objects::nonNull)
                .forEach(subjects::add);
        var linked = identities.accountsForSubjects(policy.getIdentityProviderId(), subjects);
        var accounts = identities.accounts(
                current.stream().map(WorkspaceAccountMembership::getAccountId).collect(Collectors.toSet()));
        DirectorySnapshot display =
                policy.getPreviewSnapshot() != null ? policy.getPreviewSnapshot() : policy.getActiveSnapshot();
        List<DirectoryMemberDTO> inventory = inventory(display, current, linked, accounts);
        DirectoryEvidenceDTO approved =
                evidence(policy, source, policy.getActiveSnapshot(), current, false, connected, linked, accounts);
        DirectoryEvidenceDTO preview =
                evidence(policy, source, policy.getPreviewSnapshot(), current, true, connected, linked, accounts);
        if (policy.getStatus() == DirectoryPolicy.Status.ACTIVE && (approved == null || !approved.fresh()))
            blockers.add(
                    "New access needs fresh evidence for the approved policy. Existing access is retained until a departure is confirmed; an administrator can reconcile now.");
        return new DirectoryPolicyDTO(
                policy.getConnectionId(),
                policy.getRegistrationId(),
                policy.getIssuer(),
                policy.getStatus(),
                policy.getHealth(),
                policy.getConfigurationVersion(),
                policy.getDraftGroupIds(),
                policy.getApprovedGroupIds(),
                policy.getApprovedAt(),
                policy.getLastAttemptAt(),
                policy.getFailureReason(),
                List.copyOf(blockers),
                approved,
                preview,
                inventory);
    }

    private @Nullable DirectoryEvidenceDTO evidence(
            DirectoryPolicy policy,
            @Nullable Source source,
            @Nullable DirectorySnapshot snapshot,
            List<WorkspaceAccountMembership> current,
            boolean preview,
            boolean connected,
            Map<String, AccountIdentityQuery.AccountView> linked,
            Map<Long, AccountIdentityQuery.AccountView> accounts) {
        if (snapshot == null) return null;
        var inventory = inventory(snapshot, current, linked, accounts);
        int linkedEligible =
                (int) inventory.stream().filter(DirectoryMemberDTO::eligible).count();
        return new DirectoryEvidenceDTO(
                snapshot.startedAt(),
                snapshot.completedAt(),
                connected
                        && source != null
                        && policies.usable(
                                policy,
                                source,
                                snapshot,
                                preview ? policy.getDraftGroupIds() : policy.getApprovedGroupIds()),
                snapshot.groupNames(),
                snapshot.eligibleSubjects().size(),
                Math.max(0, snapshot.eligibleSubjects().size() - linkedEligible),
                (int) inventory.stream()
                        .filter(member -> member.change() == Change.ADD)
                        .count(),
                (int) inventory.stream()
                        .filter(member -> member.change() == Change.REMOVE)
                        .count());
    }

    private List<DirectoryMemberDTO> inventory(
            @Nullable DirectorySnapshot snapshot,
            List<WorkspaceAccountMembership> current,
            Map<String, AccountIdentityQuery.AccountView> linked,
            Map<Long, AccountIdentityQuery.AccountView> accounts) {
        Map<Long, String> eligible = new HashMap<>();
        if (snapshot != null)
            snapshot.eligibleSubjects().keySet().forEach(subject -> {
                var account = linked.get(subject);
                if (account != null && account.active()) eligible.put(account.id(), subject);
            });
        List<DirectoryMemberDTO> result = new ArrayList<>();
        for (var member : current) {
            boolean qualifies = eligible.remove(member.getAccountId()) != null;
            boolean protectedAccess = member.isSuspended()
                    || member.getRole() == WorkspaceRole.OWNER
                    || member.getSource() != WorkspaceAccountMembership.Source.DIRECTORY;
            boolean departed = snapshot != null
                    && member.getDirectorySubject() != null
                    && (snapshot.confirmedDepartures().contains(member.getDirectorySubject())
                            || !member.getAccountId()
                                    .equals(Optional.ofNullable(linked.get(member.getDirectorySubject()))
                                            .map(AccountIdentityQuery.AccountView::id)
                                            .orElse(null)));
            Change change = protectedAccess ? Change.PROTECTED : departed ? Change.REMOVE : Change.UNCHANGED;
            result.add(new DirectoryMemberDTO(
                    member.getAccountId(),
                    Optional.ofNullable(accounts.get(member.getAccountId()))
                            .map(AccountIdentityQuery.AccountView::displayName)
                            .orElse("Deleted account"),
                    member.getRole(),
                    member.getSource(),
                    member.isSuspended(),
                    qualifies,
                    qualifies
                            && member.getRole() != WorkspaceRole.OWNER
                            && member.getSource() != WorkspaceAccountMembership.Source.DIRECTORY,
                    change));
        }
        eligible.forEach((id, subject) -> result.add(new DirectoryMemberDTO(
                id,
                java.util.Objects.requireNonNull(linked.get(subject)).displayName(),
                null,
                null,
                false,
                true,
                false,
                Change.ADD)));
        result.sort(java.util.Comparator.comparing(DirectoryMemberDTO::displayName)
                .thenComparing(DirectoryMemberDTO::accountId));
        return List.copyOf(result);
    }
}
