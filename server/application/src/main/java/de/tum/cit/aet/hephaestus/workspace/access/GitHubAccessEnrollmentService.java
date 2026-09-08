package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
public class GitHubAccessEnrollmentService {
    private final GitHubAccessMembershipRepository members;
    private final AccountIdentityQuery identities;
    private final GitHubAccessActionRepository actions;
    private final ConfigAuditPort audit;

    /** Opting in only changes preference; the reconciler still requires current approved eligibility. */
    @Transactional
    public void enroll(long targetId, boolean enrolled) {
        long accountId = CurrentAccount.requireId();
        identities
                .accountForUpdate(accountId)
                .filter(AccountIdentityQuery.AccountView::active)
                .orElseThrow(() -> new IllegalArgumentException("An active account is required"));
        var member = members.findByAccountId(accountId).stream()
                .filter(value -> value.getTarget().getId() == targetId)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalArgumentException("This GitHub access target is not offered to your account"));
        if (enrolled && member.getTarget().getStatus() != GitHubAccessTarget.Status.ACTIVE)
            throw new IllegalArgumentException("This GitHub target is not accepting enrollment");
        var before = GitHubAccessAudit.Membership.of(member);
        member.setEnrolled(enrolled);
        if (!enrolled) {
            boolean pending = actions
                    .findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                            member.getWorkspace().getId(),
                            targetId,
                            java.util.Set.of(
                                    GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY))
                    .stream()
                    .anyMatch(action -> action.getMembership().getId().equals(member.getId()));
            if (member.isManaged() || pending) member.setRevocationRequested(true);
            member.setBlocker("You left this target; any managed access remains until GitHub confirms removal");
        }
        // Rejoining cannot erase a departure already queued for an old identity or unconfirmed grant.
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.GITHUB_ACCESS_MEMBERSHIP,
                member.getId(),
                member.getWorkspace().getId(),
                before,
                GitHubAccessAudit.Membership.of(member)));
    }
}
