package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.workspace.HiddenFormerMember;
import de.tum.cit.aet.hephaestus.workspace.HiddenFormerMemberRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PublicActivityObjectionService {
    private final HiddenFormerMemberRepository hidden;
    private final WorkspaceMembershipRepository memberships;
    private final WorkspaceRepository workspaces;
    private final ActivityPeopleQueryRepository people;
    private final ConfigAuditPort audit;
    private final PersonDataCopyFence copyFence;
    private final PersonDataWriteFence writeFence;

    @Transactional(readOnly = true)
    public long hiddenPeople(long workspaceId) {
        return people.countPublicHiddenPeople(workspaceId);
    }

    @Transactional
    public void hide(long workspaceId, long userId, boolean hide) {
        copyFence.holdForCapture();
        if (!writeFence.holdForUserWrite(userId) || !people.canClassify(workspaceId, userId)) {
            throw new EntityNotFoundException("Contributor", userId);
        }
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var member = memberships.findByWorkspace_IdAndUser_Id(workspaceId, userId);
        boolean before = member.map(m -> m.isHidden())
                .orElseGet(() -> hidden.existsById(new HiddenFormerMember.Key(workspaceId, userId)));
        if (before == hide) return;
        if (member.isPresent()) {
            member.get().setHidden(hide);
        } else if (hide) {
            hidden.save(new HiddenFormerMember(workspaceId, userId));
        } else {
            hidden.deleteByWorkspaceIdAndUserId(workspaceId, userId);
        }
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_VISIBILITY,
                workspace.getId(),
                workspaceId,
                new ObjectionSnapshot(before),
                new ObjectionSnapshot(hide)));
    }

    record ObjectionSnapshot(boolean contributorHidden) implements ConfigAuditSnapshot {}
}
