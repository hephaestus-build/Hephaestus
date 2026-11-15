package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityAutomation;
import de.tum.cit.aet.hephaestus.activity.ActivityAutomationRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityPersonWriteAdmission;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.integration.scm.spi.ActivityScmFacts;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ActivityAutomationService {
    private final ActivityAutomationRepository automation;
    private final WorkspaceQueries workspaces;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private final ActivityScmFacts scm;
    private final ActivityPeopleQueryRepository people;
    private final ConfigAuditPort audit;
    private final PersonDataCopyFence fence;
    private final ActivityPersonWriteAdmission admission;

    @Transactional
    public void classify(long workspaceId, long userId, boolean treatAsAutomation) {
        fence.holdForCapture();
        if (!admission.holdActor(userId)) {
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
            classification.setWorkspace(
                    Objects.requireNonNull(entityManager).getReference(Workspace.class, workspace.id()));
            classification.setUser(scm.userReference(userId));
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
