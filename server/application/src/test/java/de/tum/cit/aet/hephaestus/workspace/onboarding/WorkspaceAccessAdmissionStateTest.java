package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.LoginProviderQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class WorkspaceAccessAdmissionStateTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final WorkspaceAccessPolicyRepository policies = mock(WorkspaceAccessPolicyRepository.class);
    private final WorkspaceAccountMembershipRepository memberships = mock(WorkspaceAccountMembershipRepository.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final WorkspaceAccessCatalog catalog = mock(WorkspaceAccessCatalog.class);
    private final WorkspaceAccessAdmissionState state = new WorkspaceAccessAdmissionState(
            workspaces,
            policies,
            memberships,
            identities,
            catalog,
            mock(ConfigAuditPort.class),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final Workspace workspace = new Workspace();
    private final WorkspaceAccessPolicy policy = new WorkspaceAccessPolicy();

    @BeforeEach
    void setUp() {
        workspace.setId(10L);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        var organization = new Organization();
        organization.setNativeId(77L);
        workspace.setOrganization(organization);
        policy.setWorkspace(workspace);
        policy.setEnabled(true);
        policy.setVersion(3);
        policy.setSettings(new WorkspaceAccessPolicySettings(
                "github",
                "Welcome",
                "I agree",
                List.of(),
                List.of(),
                1L,
                List.of(),
                90,
                14,
                "admins@example.test",
                null));
        when(workspaces.findById(10L)).thenReturn(Optional.of(workspace));
        when(workspaces.findByIdForUpdate(10L)).thenReturn(Optional.of(workspace));
        when(policies.findByWorkspace_Id(10L)).thenReturn(Optional.of(policy));
        when(identities.accountForUpdate(2L))
                .thenReturn(Optional.of(new AccountIdentityQuery.AccountView(2L, "Applicant", true)));
        when(catalog.primary(workspace, policy.getSettings()))
                .thenReturn(new LoginProviderQuery.Provider("github", "GitHub", "GITHUB", "https://github.com"));
        when(catalog.primaryLink(workspace, policy.getSettings(), 2L)).thenReturn(11L);
        when(identities.activeLinksForAccount(2L)).thenReturn(List.of(link("123")));
    }

    @Test
    void shouldGrantOnlyMemberAccessToThePreparedNativeIdentity() {
        var prepared = state.prepare(10L, 2L);
        assertThat(prepared.target().organizationNativeId()).isEqualTo(77L);
        assertThat(prepared.target().userNativeId()).isEqualTo(123L);
        assertThat(state.admit(prepared, NOW)).isEqualTo(WorkspaceAccessAdmissionService.State.ACTIVE);
        var saved = ArgumentCaptor.forClass(WorkspaceAccountMembership.class);
        verify(memberships).save(saved.capture());
        assertThat(saved.getValue().getAccountId()).isEqualTo(2L);
        assertThat(saved.getValue().getRole()).isEqualTo(WorkspaceRole.MEMBER);
        assertThat(saved.getValue().getSource()).isEqualTo(WorkspaceAccountMembership.Source.SCM);
    }

    @Test
    void shouldRejectProofWhenThePolicyChangesDuringTheProviderRead() {
        var prepared = state.prepare(10L, 2L);
        policy.setVersion(4);
        assertThatThrownBy(() -> state.admit(prepared, NOW)).isInstanceOf(ResponseStatusException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRejectProofWhenTheNativeIdentityChangesDuringTheProviderRead() {
        var prepared = state.prepare(10L, 2L);
        when(identities.activeLinksForAccount(2L)).thenReturn(List.of(link("456")));
        assertThatThrownBy(() -> state.admit(prepared, NOW)).isInstanceOf(ResponseStatusException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldRejectAnExpiredMembershipProof() {
        var prepared = state.prepare(10L, 2L);
        assertThatThrownBy(() -> state.admit(prepared, NOW.minusSeconds(31)))
                .isInstanceOf(ResponseStatusException.class);
        verify(memberships, never()).save(any());
    }

    @Test
    void shouldPreserveASuspensionRecordedDuringTheProviderRead() {
        var prepared = state.prepare(10L, 2L);
        var suspended = new WorkspaceAccountMembership();
        suspended.setWorkspace(workspace);
        suspended.setAccountId(2L);
        suspended.setSource(WorkspaceAccountMembership.Source.REQUEST);
        suspended.setSuspended(true);
        when(memberships.findByWorkspace_IdAndAccountId(10L, 2L)).thenReturn(Optional.of(suspended));
        assertThat(state.admit(prepared, NOW)).isEqualTo(WorkspaceAccessAdmissionService.State.MANAGED);
        verify(memberships, never()).save(any());
    }

    private static AccountIdentityQuery.IdentityLinkView link(String subject) {
        return new AccountIdentityQuery.IdentityLinkView(
                11L, 3L, subject, "username-is-not-proof", "Applicant", null, null, null, null);
    }
}
