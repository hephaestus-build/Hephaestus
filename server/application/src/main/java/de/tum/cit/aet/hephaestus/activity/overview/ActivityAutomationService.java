package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityAutomation;
import de.tum.cit.aet.hephaestus.activity.ActivityAutomationRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ActivityAutomationService {
    private final ActivityAutomationRepository automation;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final ActivityPeopleQueryRepository people;
    private final ConfigAuditPort audit;
    private final PersonDataCopyFence fence;
    private final PersonDataWriteFence writeFence;

    @Transactional
    public void classify(long workspaceId, long userId, boolean treatAsAutomation) {
        fence.holdForCapture();
        if (!writeFence.holdForUserWrite(userId)) {
            throw new EntityNotFoundException("Contributor", userId);
        }
        var workspace = workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId));
        boolean visible = people.canClassify(workspaceId, userId);
        if (!visible) {
            throw new EntityNotFoundException("Contributor", userId);
        }
        var id = new ActivityAutomation.Id(workspaceId, userId);
        boolean before = automation.existsById(id);
        if (before == treatAsAutomation) {
            return;
        }
        if (treatAsAutomation) {
            var classification = new ActivityAutomation();
            classification.setWorkspace(workspace);
            classification.setUser(users.getReferenceById(userId));
            automation.save(classification);
        } else {
            automation.deleteById(id);
        }
        audit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.ACTIVITY_AUTOMATION,
                userId,
                workspaceId,
                new AutomationSnapshot(before),
                new AutomationSnapshot(treatAsAutomation)));
    }

    record AutomationSnapshot(boolean treatAsAutomation) implements ConfigAuditSnapshot {}
}
