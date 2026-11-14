package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceMembershipsAdapter implements WorkspaceMemberships {
    private final MembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final MembershipService memberVisibility;
    private final Clock clock;
    private final ConfigAuditPort audit;

    @Override
    public Optional<WorkspaceRole> activeRole(long workspaceId, long accountId) {
        return memberships
                .findByWorkspace_IdAndId_AccountId(workspaceId, accountId)
                .filter(member -> member.getLeftAt() == null)
                .map(Membership::getRole);
    }

    @Override
    public boolean hasMembership(long workspaceId, long accountId) {
        return memberships
                .findByWorkspace_IdAndId_AccountId(workspaceId, accountId)
                .isPresent();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long accountIdForUser(long scmUserId) {
        return memberVisibility.accountIdForUser(scmUserId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void setContributorHidden(long workspaceId, long accountId, boolean hidden) {
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var member = memberships.findByWorkspace_IdAndId_AccountId(workspaceId, accountId);
        boolean before = member.map(Membership::isHidden).orElse(false);
        if (before == hidden) return;
        if (member.isPresent() && member.get().getLeftAt() == null) {
            memberVisibility.updateMemberVisibility(workspaceId, accountId, hidden);
            return;
        }
        var retainedMember = member.orElseGet(() -> {
            var created = memberVisibility.createMembership(workspace, accountId, Membership.WorkspaceRole.MEMBER);
            created.setLeftAt(clock.instant());
            return created;
        });
        retainedMember.setHidden(hidden);
        memberships.save(retainedMember);
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_VISIBILITY,
                workspace.getId(),
                workspaceId,
                new ObjectionSnapshot(before),
                new ObjectionSnapshot(hidden)));
    }

    record ObjectionSnapshot(boolean contributorHidden) implements ConfigAuditSnapshot {}
}
