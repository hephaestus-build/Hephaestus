package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityAutomation;
import de.tum.cit.aet.hephaestus.activity.ActivityAutomationRepository;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ActivityAutomationService {
    private final ActivityAutomationRepository automation;
    private final WorkspaceRepository workspaces;
    private final UserRepository users;
    private final NamedParameterJdbcTemplate jdbc;
    private final ConfigAuditPort audit;

    @Transactional
    public void classify(long workspaceId, long userId, boolean treatAsAutomation) {
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        boolean visible = Boolean.TRUE.equals(
                jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM activity_event e WHERE e.workspace_id = :workspace AND e.actor_id = :person)
                    OR EXISTS (SELECT 1 FROM workspace_membership m WHERE m.workspace_id = :workspace AND m.user_id = :person)
                """, Map.of("workspace", workspaceId, "person", userId), Boolean.class));
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
                ConfigAuditEntityType.WORKSPACE_ROLE,
                userId,
                workspaceId,
                new AutomationSnapshot(before),
                new AutomationSnapshot(treatAsAutomation)));
    }

    record AutomationSnapshot(boolean treatAsAutomation) implements ConfigAuditSnapshot {}
}
