package de.tum.cit.aet.hephaestus.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceSettingsService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * One test per config-audit producer. {@code ConfigAuditRecorder} throws outside a writable
 * transaction, so an unexercised producer is a latent 500 rather than merely a missing row — and it
 * drops an UPDATE whose diff is empty, which is silent when a snapshot is built so it never differs.
 */
@de.tum.cit.aet.hephaestus.testconfig.WithAdminUser
class ConfigAuditProducerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WorkspaceMembershipService membershipService;

    @Autowired
    private de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipService accountMembershipService;

    @Autowired
    private de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipSync accountSync;

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

        accountMembershipService.assign(workspace.getId(), accountId(member), WorkspaceMembership.WorkspaceRole.MEMBER);
        accountMembershipService.assign(workspace.getId(), accountId(member), WorkspaceMembership.WorkspaceRole.ADMIN);

        assertThat(actionsForMember(workspace, member))
                .containsExactly(ConfigAuditAction.CREATED, ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void revokingAMembershipIsRecorded() {
        Workspace workspace = workspace("audit-producer-revoke");
        User member = persistUser("audit-producer-revoke-member");
        accountMembershipService.assign(workspace.getId(), accountId(member), WorkspaceMembership.WorkspaceRole.ADMIN);

        accountMembershipService.suspend(workspace.getId(), accountId(member));

        assertThat(actionsForMember(workspace, member))
                .containsExactly(ConfigAuditAction.CREATED, ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void hidingAMemberIsRecordedBecauseItOutlivesOrgRemoval() {
        Workspace workspace = workspace("audit-producer-hidden");
        User member = persistUser("audit-producer-hidden-member");
        membershipService.createMembership(workspace, member.getId(), WorkspaceMembership.WorkspaceRole.MEMBER);

        membershipService.updateMemberVisibility(workspace.getId(), member.getId(), true);

        assertThat(snapshotsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE))
                .as("hidden preferences survive organization synchronization")
                .anyMatch(newValue -> newValue.contains("\"hidden\":true"));
    }

    @Test
    @Transactional
    void aRoleChangedByOrgSyncIsRecorded() {
        Workspace workspace = workspace("audit-producer-sync");
        User member = persistUser("audit-producer-sync-member");
        accountSync.synchronize(workspace, Map.of(member.getId(), WorkspaceMembership.WorkspaceRole.MEMBER));

        membershipService.syncWorkspaceMembers(
                workspace, Map.of(member.getId(), WorkspaceMembership.WorkspaceRole.ADMIN));

        assertThat(actionsForMember(workspace, member)).contains(ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void togglingPublicVisibilityIsRecorded() {
        Workspace workspace = workspace("audit-producer-visibility");

        settingsService.updatePublicVisibility(workspace.getId(), true);

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_VISIBILITY))
                .containsExactly(ConfigAuditAction.UPDATED);
    }

    @Test
    @Transactional
    void aNoOpVisibilityUpdateIsNotRecorded() {
        Workspace workspace = workspace("audit-producer-noop");
        settingsService.updatePublicVisibility(workspace.getId(), true);

        settingsService.updatePublicVisibility(workspace.getId(), true);

        assertThat(actionsFor(workspace, ConfigAuditEntityType.WORKSPACE_VISIBILITY))
                .as("re-submitting a form unchanged must not add a row that says nothing")
                .containsExactly(ConfigAuditAction.UPDATED);
    }

    private Workspace workspace(String slug) {
        Workspace workspace =
                createWorkspace(slug, "Audit Workspace", slug + "-org", AccountType.ORG, persistUser(slug + "-owner"));
        ensureAdminMembership(workspace);
        WorkspaceContextHolder.setContext(
                WorkspaceContext.fromWorkspace(workspace, Set.of(WorkspaceMembership.WorkspaceRole.ADMIN), null));
        return workspace;
    }

    private List<ConfigAuditAction> actionsForMember(Workspace workspace, User member) {
        var id = accountId(member).toString();
        return rowsFor(workspace, ConfigAuditEntityType.WORKSPACE_ROLE).stream()
                .filter(row -> id.equals(row.getEntityId()))
                .map(ConfigAuditEvent::getAction)
                .toList();
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

    /** Not {@code findAll()}: the tenancy filter empties an unscoped read when no workspace is bound. */
    private List<ConfigAuditEvent> rowsFor(Workspace workspace, ConfigAuditEntityType entityType) {
        entityManager.flush();
        var filter = new ConfigAuditFilter(List.of(entityType), null, null, null, null, null, null);
        return repository.findForWorkspace(workspace.getId(), filter, PageRequest.of(0, 50)).getContent().stream()
                .sorted(java.util.Comparator.comparing(ConfigAuditEvent::getId))
                .toList();
    }
}
