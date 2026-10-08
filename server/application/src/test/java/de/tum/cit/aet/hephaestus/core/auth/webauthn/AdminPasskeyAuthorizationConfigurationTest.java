package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.AdminAccess;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

@Tag("unit")
class AdminPasskeyAuthorizationConfigurationTest {
    @RequireInstanceAdmin
    static class InstanceEndpoints {
        public void read() {}

        @PreAuthorize("isAuthenticated()")
        public void ordinary() {}
    }

    static class WorkspaceEndpoints {
        @RequireAtLeastWorkspaceAdmin
        public void admin() {}

        @RequireWorkspaceOwner
        public void owner() {}

        @PreAuthorize("@workspaceSecure.isMember()")
        public void member() {}
    }

    @Test
    void shouldProtectClassScopedInstanceAdministration() throws Exception {
        assertThat(AdminPasskeyAuthorizationConfiguration.scope(
                        InstanceEndpoints.class.getMethod("read"), InstanceEndpoints.class))
                .isEqualTo(AdminAccess.Scope.INSTANCE);
    }

    @Test
    void shouldProtectWorkspaceAdminAndOwnerAdministration() throws Exception {
        for (String name : new String[] {"admin", "owner"}) {
            assertThat(AdminPasskeyAuthorizationConfiguration.scope(
                            WorkspaceEndpoints.class.getMethod(name), WorkspaceEndpoints.class))
                    .isEqualTo(AdminAccess.Scope.WORKSPACE);
        }
    }

    @Test
    void shouldLeaveOrdinaryMemberAccessWithoutAdminAssurance() throws Exception {
        assertThat(AdminPasskeyAuthorizationConfiguration.scope(
                        WorkspaceEndpoints.class.getMethod("member"), WorkspaceEndpoints.class))
                .isNull();
    }

    @Test
    void shouldHonorMethodAuthorizationInsteadOfClassAuthorization() throws Exception {
        assertThat(AdminPasskeyAuthorizationConfiguration.scope(
                        InstanceEndpoints.class.getMethod("ordinary"), InstanceEndpoints.class))
                .isNull();
    }
}
