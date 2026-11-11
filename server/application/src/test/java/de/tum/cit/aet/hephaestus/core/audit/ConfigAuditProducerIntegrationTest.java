package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.PublicActivityChoice;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSettingsService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

class ConfigAuditProducerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private WorkspaceSettingsService settingsService;

    @Autowired
    private ConfigAuditEventRepository repository;

    private final EntityManager entityManager;

    @Autowired
    ConfigAuditProducerIntegrationTest(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @AfterEach
    void clearWorkspaceContext() {
        WorkspaceContextHolder.clearContext();
    }

    @Test
    @Transactional
    void grantingAndThenChangingARoleIsRecorded() {
        Workspace workspace = workspace("audit-producer-role");
        User member = persistUser("audit-producer-role-member");

        membershipService.assignRole(workspace.getId(), accountId(member), Membership.WorkspaceRole.MEMBER);
        membershipService.assignRole(workspace.getId(), accountId(member), Membership.WorkspaceRole.ADMIN);

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE))
                .containsExactly(ConfigAuditAction.CREATED, ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void revokingAMembershipIsRecorded() {
        Workspace workspace = workspace("audit-producer-revoke");
        User member = persistUser("audit-producer-revoke-member");
        membershipService.assignRole(workspace.getId(), accountId(member), Membership.WorkspaceRole.ADMIN);

        membershipService.removeMembership(workspace.getId(), accountId(member));

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE))
                .containsExactly(ConfigAuditAction.CREATED, ConfigAuditAction.DELETED);
    }

    @Test
    @Transactional
    void hidingAMemberIsRecordedBecauseItOutlivesOrgRemoval() {
        Workspace workspace = workspace("audit-producer-hidden");
        User member = persistUser("audit-producer-hidden-member");
        membershipService.assignRole(workspace.getId(), accountId(member), Membership.WorkspaceRole.MEMBER);

        membershipService.updateMemberVisibility(workspace.getId(), accountId(member), true);

        assertThat(snapshotsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE))
                .as("hidden decides whether access survives leaving the org")
                .anyMatch(newValue -> newValue.contains("\"hidden\":true"));
    }

    @Test
    @Transactional
    void aRoleChangedByOrgSyncIsRecorded() {
        Workspace workspace = workspace("audit-producer-sync");
        User member = persistUser("audit-producer-sync-member");
        // A role the provider set: sync may change it, unlike one that a person set.
        membershipService.createMembership(workspace, accountId(member), Membership.WorkspaceRole.MEMBER);

        membershipService.syncWorkspaceMembers(workspace, Map.of(member.getId(), Membership.WorkspaceRole.ADMIN));

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE)).contains(ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void togglingPublicVisibilityIsRecorded() {
        Workspace workspace = workspace("audit-producer-visibility");

        settingsService.updatePublicActivity(workspace.getId(), new PublicActivityChoice(true, false, false));

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_VISIBILITY))
                .containsExactly(ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void aNoOpVisibilityUpdateIsNotRecorded() {
        Workspace workspace = workspace("audit-producer-noop");
        settingsService.updatePublicActivity(workspace.getId(), new PublicActivityChoice(true, false, false));

        settingsService.updatePublicActivity(workspace.getId(), new PublicActivityChoice(true, false, false));

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_VISIBILITY))
                .as("re-submitting a form unchanged must not add a row that says nothing")
                .containsExactly(ConfigAuditAction.UPDATED);
    }

    private Workspace workspace(String slug) {
        Workspace workspace =
                createWorkspace(slug, "Audit Workspace", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        ensureAdminMembership(workspace);
        WorkspaceContextHolder.setContext(
                WorkspaceContext.fromWorkspace(workspace, Set.of(Membership.WorkspaceRole.ADMIN), null));
        return workspace;
    }

    private List<ConfigAuditAction> actionsFor(Workspace workspace, ConfigAuditEntityType entityType) {
        return rowsFor(workspace, entityType).stream()
                .map(ConfigAuditEvent::getAction)
                .toList();
    }

    private List<String> snapshotsFor(Workspace workspace, ConfigAuditEntityType entityType) {
        return rowsFor(workspace, entityType).stream()
                .map(row -> row.getNewValue() == null ? "" : row.getNewValue())
                .toList();
    }

    // Unscoped tenancy reads are empty; bind the workspace before reading audit rows.
    private List<ConfigAuditEvent> rowsFor(Workspace workspace, ConfigAuditEntityType entityType) {
        entityManager.flush();
        var filter = new ConfigAuditFilter(List.of(entityType), null, null, null, null, null, null);
        return repository.findForWorkspace(workspace.getId(), filter, null, null, PageRequest.of(0, 50)).stream()
                .sorted(Comparator.comparing(ConfigAuditEvent::getId))
                .toList();
    }
}
