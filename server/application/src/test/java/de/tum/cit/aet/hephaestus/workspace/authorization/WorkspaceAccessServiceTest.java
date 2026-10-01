package de.tum.cit.aet.hephaestus.workspace.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContextHolder;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("unit")
class WorkspaceAccessServiceTest {

    private WorkspaceAccessService accessService;

    @BeforeEach
    void setUp() {
        accessService = new WorkspaceAccessService();
    }

    @AfterEach
    void tearDown() {
        WorkspaceContextHolder.clearContext();
        SecurityContextHolder.clearContext();
    }

    @Test
    void hasRole_WithNoContext_ReturnsFalse() {
        // Given: No workspace context is set
        // When & Then
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isFalse();
    }

    @Test
    void hasRole_WithEmptyRoles_ReturnsFalse() {
        // Given: Context with no roles
        WorkspaceContext context = new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of());
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isFalse();
    }

    @Test
    void hasRole_WithMemberRole_AllowsMemberAccess() {
        // Given: User has MEMBER role
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.MEMBER));
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isFalse();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void hasRole_WithAdminRole_AllowsAdminAndMemberAccess() {
        // Given: User has ADMIN role
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(context);

        // When & Then: Admin satisfies MEMBER and ADMIN requirements
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void hasRole_WithOwnerRole_AllowsAllAccess() {
        // Given: User has OWNER role
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        // When & Then: Owner satisfies all role requirements
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isTrue();
    }

    @Test
    void isOwner_WithOwnerRole_ReturnsTrue() {
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.isOwner()).isTrue();
    }

    @Test
    void isOwner_WithAdminRole_ReturnsFalse() {
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.isOwner()).isFalse();
    }

    @Test
    void isAdmin_WithAdminRole_ReturnsTrue() {
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_WithOwnerRole_ReturnsTrue() {
        // Given: Owner should also satisfy admin checks
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        // When & Then
        assertThat(accessService.isAdmin()).isTrue();
    }

    @Test
    void isMember_WithAnyRole_ReturnsTrue() {
        // Given: Any role should satisfy member check
        WorkspaceContext memberContext =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.MEMBER));

        // When & Then: MEMBER
        WorkspaceContextHolder.setContext(memberContext);
        assertThat(accessService.isMember()).isTrue();

        // Given: ADMIN
        WorkspaceContext adminContext =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(adminContext);
        assertThat(accessService.isMember()).isTrue();

        // Given: OWNER
        WorkspaceContext ownerContext =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(ownerContext);
        assertThat(accessService.isMember()).isTrue();
    }

    @Test
    void canManageRole_AsOwner_CanManageAllRoles() {
        // Given: User is OWNER
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        // When & Then: OWNER can manage all roles
        assertThat(accessService.canManageRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.OWNER)).isTrue();
    }

    @Test
    void canManageRole_AsAdmin_CannotManageOwner() {
        // Given: User is ADMIN
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(context);

        // When & Then: ADMIN can manage MEMBER and ADMIN, but not OWNER
        assertThat(accessService.canManageRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void canManageRole_AsMember_CannotManageAnyRole() {
        // Given: User is MEMBER
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.MEMBER));
        WorkspaceContextHolder.setContext(context);

        // When & Then: MEMBER cannot manage any roles
        assertThat(accessService.canManageRole(WorkspaceRole.MEMBER)).isFalse();
        assertThat(accessService.canManageRole(WorkspaceRole.ADMIN)).isFalse();
        assertThat(accessService.canManageRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void canManageRole_WithNoContext_ReturnsFalse() {
        // Given: No context
        // When & Then
        assertThat(accessService.canManageRole(WorkspaceRole.MEMBER)).isFalse();
    }

    @Test
    void hasPermission_IsAliasForHasRole() {
        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.ADMIN));
        WorkspaceContextHolder.setContext(context);

        // When & Then: hasPermission behaves same as hasRole
        assertThat(accessService.hasPermission(WorkspaceRole.MEMBER))
                .isEqualTo(accessService.hasRole(WorkspaceRole.MEMBER));
        assertThat(accessService.hasPermission(WorkspaceRole.ADMIN))
                .isEqualTo(accessService.hasRole(WorkspaceRole.ADMIN));
        assertThat(accessService.hasPermission(WorkspaceRole.OWNER))
                .isEqualTo(accessService.hasRole(WorkspaceRole.OWNER));
    }

    @Test
    void hasRole_WithSuperAdminButNoWorkspaceMembership_DeniesAccess() {
        // Given: Global admin user with no workspace membership
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "admin-user", "admin-123", new String[] {"app_admin"}, "super-admin-token"));

        WorkspaceContext context = new WorkspaceContext(
                1L, "test", "Test", AccountType.ORG, 123L, false, Set.of() // No workspace roles - not a member
                );
        WorkspaceContextHolder.setContext(context);

        // When & Then: Global admin without membership cannot access workspace
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isFalse();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isFalse();
        assertThat(accessService.isMember()).isFalse();
        assertThat(accessService.isAdmin()).isFalse();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isFalse();
        assertThat(accessService.isOwner()).isFalse();
    }

    @Test
    void hasRole_WithSuperAdminAndMemberRole_AllowsAdminAccess() {
        // Given: Global admin user who is also a MEMBER of the workspace
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "admin-user", "admin-123", new String[] {"app_admin"}, "super-admin-token"));

        WorkspaceContext context = new WorkspaceContext(
                1L,
                "test",
                "Test",
                AccountType.ORG,
                123L,
                false,
                Set.of(WorkspaceRole.MEMBER) // Only MEMBER role in workspace
                );
        WorkspaceContextHolder.setContext(context);

        // When & Then: Global admin can access ADMIN endpoints even though workspace role is MEMBER
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void hasRole_WithSuperAdminAndOwnerRole_KeepsOwnerPermissions() {
        // Given: Global admin user who is also OWNER of the workspace
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "admin-user", "admin-123", new String[] {"app_admin"}, "super-admin-token"));

        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.OWNER));
        WorkspaceContextHolder.setContext(context);

        // When & Then: Workspace OWNER role still grants all permissions
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isTrue();
    }

    @Test
    void canManageRole_WithSuperAdminAndMembership_CanManageAdminAndMember() {
        // Given: Global admin user with MEMBER workspace role
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "admin-user", "admin-123", new String[] {"app_admin"}, "super-admin-token"));

        WorkspaceContext context = new WorkspaceContext(
                1L,
                "test",
                "Test",
                AccountType.ORG,
                123L,
                false,
                Set.of(WorkspaceRole.MEMBER) // Has workspace membership
                );
        WorkspaceContextHolder.setContext(context);

        // When & Then: Global admin with membership can manage ADMIN and MEMBER roles but not OWNER
        assertThat(accessService.canManageRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.ADMIN)).isTrue();
        assertThat(accessService.canManageRole(WorkspaceRole.OWNER)).isFalse();
    }

    @Test
    void hasRole_WithNonAdminUser_DoesNotGetElevation() {
        // Given: Regular user without admin realm role
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "regular-user",
                "user-123",
                new String[] {"user"}, // Not "admin"
                "regular-user-token"));

        WorkspaceContext context =
                new WorkspaceContext(1L, "test", "Test", AccountType.ORG, 123L, false, Set.of(WorkspaceRole.MEMBER));
        WorkspaceContextHolder.setContext(context);

        // When & Then: Regular user with MEMBER role cannot access ADMIN endpoints
        assertThat(accessService.hasRole(WorkspaceRole.MEMBER)).isTrue();
        assertThat(accessService.hasRole(WorkspaceRole.ADMIN)).isFalse();
        assertThat(accessService.hasRole(WorkspaceRole.OWNER)).isFalse();
    }
}
