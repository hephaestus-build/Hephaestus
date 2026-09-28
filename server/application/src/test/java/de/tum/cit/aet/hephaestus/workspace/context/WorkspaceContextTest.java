package de.tum.cit.aet.hephaestus.workspace.context;

import static org.junit.jupiter.api.Assertions.*;

import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class WorkspaceContextTest {

    @Test
    void shouldCreateContextFromWorkspaceEntity() {
        Workspace workspace = new Workspace();
        workspace.setId(42L);
        workspace.setWorkspaceSlug("test-workspace");
        workspace.setDisplayName("Test Workspace");
        workspace.setAccountType(AccountType.ORG);

        Set<WorkspaceRole> roles = Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN);

        // Act — installationId is now passed in by the caller (resolved from the active
        // GitHub App Connection); the record no longer pulls it from Workspace.
        WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, roles, 123L);

        assertEquals(42L, context.id());
        assertEquals("test-workspace", context.slug());
        assertEquals("Test Workspace", context.displayName());
        assertEquals(AccountType.ORG, context.accountType());
        assertEquals(123L, context.installationId());
        assertEquals(2, context.roles().size());
        assertTrue(context.hasRole(WorkspaceRole.OWNER));
        assertTrue(context.hasRole(WorkspaceRole.ADMIN));
    }

    @Test
    void shouldHandleNullRolesAsEmptySet() {
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test");
        workspace.setDisplayName("Test");
        workspace.setAccountType(AccountType.USER);

        WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, null, /* installationId */ null);

        assertNotNull(context.roles());
        assertTrue(context.roles().isEmpty());
        assertFalse(context.hasMembership());
    }

    @Test
    void shouldCheckIfUserHasSpecificRole() {
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, null, false, Set.of(WorkspaceRole.MEMBER));

        assertTrue(context.hasRole(WorkspaceRole.MEMBER));
        assertFalse(context.hasRole(WorkspaceRole.OWNER));
        assertFalse(context.hasRole(WorkspaceRole.ADMIN));
    }

    @Test
    void shouldCheckIfUserHasAnyMembership() {
        WorkspaceContext withRoles =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, null, false, Set.of(WorkspaceRole.MEMBER));

        WorkspaceContext withoutRoles =
                new WorkspaceContext(2L, "test2", "Test2", AccountType.USER, null, false, Set.of());

        assertTrue(withRoles.hasMembership());
        assertFalse(withoutRoles.hasMembership());
    }

    @Test
    void shouldHandleNullInstallationId() {
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test");
        workspace.setDisplayName("Test");
        workspace.setAccountType(AccountType.USER);

        // Act — null installationId is the legitimate "no App connection" path.
        WorkspaceContext context = WorkspaceContext.fromWorkspace(workspace, Set.of(), null);

        assertNull(context.installationId());
    }

    @Test
    void shouldSupportMultipleRoles() {
        Set<WorkspaceRole> roles = Set.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN, WorkspaceRole.MEMBER);

        WorkspaceContext context = new WorkspaceContext(1L, "test", "Test", AccountType.ORG, null, false, roles);

        assertTrue(context.hasRole(WorkspaceRole.OWNER));
        assertTrue(context.hasRole(WorkspaceRole.ADMIN));
        assertTrue(context.hasRole(WorkspaceRole.MEMBER));
        assertTrue(context.hasMembership());
    }

    @Test
    void shouldBeImmutableRecord() {
        Set<WorkspaceRole> roles = Set.of(WorkspaceRole.OWNER);
        WorkspaceContext context = new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 100L, false, roles);

        // Act - Try to get roles and verify they're the same set
        Set<WorkspaceRole> retrievedRoles = context.roles();

        // Assert - Record properties should be accessible
        assertEquals(1L, context.id());
        assertEquals("test", context.slug());
        assertEquals("Test", context.displayName());
        assertEquals(AccountType.ORG, context.accountType());
        assertEquals(100L, context.installationId());
        assertSame(roles, retrievedRoles);
    }
}
