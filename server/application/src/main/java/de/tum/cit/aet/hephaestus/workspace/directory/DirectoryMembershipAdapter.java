package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityUnlinkParticipant;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership.Source;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots.AccountMembershipSnapshot;
import de.tum.cit.aet.hephaestus.workspace.spi.DirectorySubjectRetention;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Policy mutations hold the workspace lock; identity unlink participates under its account lock. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class DirectoryMembershipAdapter implements IdentityUnlinkParticipant {
    private final WorkspaceAccountMembershipRepository memberships;
    private final AccountIdentityQuery identities;
    private final ConfigAuditPort audit;
    private final DirectoryPolicyRepository policies;
    private final List<DirectorySubjectRetention> retainedSubjects;

    public List<String> retainedSubjects(long workspaceId, long providerId) {
        return retainedSubjects.stream()
                .flatMap(retention -> retention.retainedSubjects(workspaceId, providerId).stream())
                .distinct()
                .toList();
    }

    @Override
    public void beforeUnlink(Long accountId, Long providerId, String subject) {
        // Do not take workspace locks here: admission locks workspace then account, whereas unlink
        // already holds the account. The account lock serializes adoption and manual exceptions too.
        for (var membership : memberships.findActiveByAccountId(accountId)) {
            if (membership.getSource() != Source.DIRECTORY
                    || membership.getRole() == WorkspaceRole.OWNER
                    || !subject.equals(membership.getDirectorySubject())) continue;
            policies.findByWorkspace_Id(membership.getWorkspace().getId())
                    .filter(policy -> providerId.equals(policy.getIdentityProviderId()))
                    .ifPresent(policy -> remove(membership));
        }
    }

    public void apply(DirectoryPolicy policy, long providerId, DirectorySnapshot snapshot, boolean allowGrants) {
        var workspace = policy.getWorkspace();
        var current = memberships.findByWorkspace_Id(workspace.getId());
        var linked = identities.accountsForSubjects(
                providerId,
                Stream.concat(
                                snapshot.eligibleSubjects().keySet().stream(),
                                current.stream().map(WorkspaceAccountMembership::getDirectorySubject))
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet()));
        // A person can link multiple organizational sources. Lock accounts in one global order even
        // when two workspaces reconcile different issuers, and before touching membership rows.
        Stream.concat(
                        current.stream().map(WorkspaceAccountMembership::getAccountId),
                        linked.values().stream().map(AccountIdentityQuery.AccountView::id))
                .distinct()
                .sorted()
                .forEach(identities::accountForUpdate);
        for (var membership : current) {
            if (membership.getSource() != Source.DIRECTORY
                    || membership.getRole() == WorkspaceRole.OWNER
                    || membership.isSuspended()) continue;
            String subject = membership.getDirectorySubject();
            var account = subject == null ? null : linked.get(subject);
            boolean stillLinked = account != null && account.id().equals(membership.getAccountId());
            if (!stillLinked || snapshot.confirmedDepartures().contains(subject)) remove(membership);
        }
        if (allowGrants) {
            linked.entrySet().stream()
                    .filter(entry -> entry.getValue().active()
                            && snapshot.eligibleSubjects().containsKey(entry.getKey()))
                    .sorted(Map.Entry.comparingByValue(
                            java.util.Comparator.comparing(AccountIdentityQuery.AccountView::id)))
                    .forEach(entry ->
                            grant(workspace, providerId, entry.getValue().id(), entry.getKey()));
        }
    }

    public void grant(Workspace workspace, long providerId, long accountId, String subject) {
        if (memberships
                .findByWorkspace_IdAndAccountId(workspace.getId(), accountId)
                .isPresent()) return;
        // Account locking serializes admission with identity unlinking and account erasure.
        if (identities
                        .accountForUpdate(accountId)
                        .filter(AccountIdentityQuery.AccountView::active)
                        .isEmpty()
                || identities
                        .resolveActiveAccountId(providerId, subject, null)
                        .filter(id -> id == accountId)
                        .isEmpty()) return;
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        membership.setRole(WorkspaceRole.MEMBER);
        membership.setSource(Source.DIRECTORY);
        membership.setDirectorySubject(subject);
        memberships.save(membership);
        audit.record(ConfigAuditEntry.created(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                accountId,
                workspace.getId(),
                AccountMembershipSnapshot.of(membership)));
    }

    public void adopt(WorkspaceAccountMembership membership, String subject) {
        if (membership.getRole() == WorkspaceRole.OWNER)
            throw new IllegalArgumentException("Ownership must remain explicitly assigned, never directory-managed");
        var before = AccountMembershipSnapshot.of(membership);
        membership.setSource(Source.DIRECTORY);
        membership.setDirectorySubject(subject);
        membership.setExpiresAt(null);
        membership.setAccessRequestId(null);
        membership.setRole(WorkspaceRole.MEMBER);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                membership.getAccountId(),
                membership.getWorkspace().getId(),
                before,
                AccountMembershipSnapshot.of(membership)));
    }

    public void end(Workspace workspace) {
        for (var membership : memberships.findByWorkspace_Id(workspace.getId()).stream()
                .sorted(java.util.Comparator.comparing(WorkspaceAccountMembership::getAccountId))
                .toList()) {
            if (membership.getSource() == Source.DIRECTORY
                    && membership.getRole() != WorkspaceRole.OWNER
                    && !membership.isSuspended()) remove(membership);
        }
    }

    private void remove(WorkspaceAccountMembership membership) {
        identities.accountForUpdate(membership.getAccountId());
        var before = AccountMembershipSnapshot.of(membership);
        memberships.delete(membership);
        audit.record(ConfigAuditEntry.deleted(
                ConfigAuditEntityType.WORKSPACE_ROLE,
                membership.getAccountId(),
                membership.getWorkspace().getId(),
                before));
    }
}
