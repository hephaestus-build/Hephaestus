package de.tum.cit.aet.hephaestus.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScope;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceTeamScopeResolver;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceApprovedAccessQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GitHubAccessRequestEvidenceTest {
    private final WorkspaceApprovedAccessQuery approvals = mock(WorkspaceApprovedAccessQuery.class);
    private final AccountIdentityQuery identities = mock(AccountIdentityQuery.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final WorkspaceTeamScopeResolver scopes = mock(WorkspaceTeamScopeResolver.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final Instant now = Instant.parse("2026-09-10T00:00:00Z");
    private final GitHubAccessRequestEvidence evidence = new GitHubAccessRequestEvidence(
            approvals, identities, providers, scopes, teams, Clock.fixed(now, ZoneOffset.UTC));
    private final GitHubAccessTarget target = new GitHubAccessTarget();
    private final Team team = new Team();
    private final AccountIdentityQuery.IdentityLinkView github =
            new AccountIdentityQuery.IdentityLinkView(50L, 1L, "61", null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        var workspace = new Workspace();
        workspace.setId(2L);
        workspace.setAccountLogin("acme");
        var organization = new Organization();
        organization.setNativeId(1000L);
        workspace.setOrganization(organization);
        target.setWorkspace(workspace);
        target.setOrganizationId(1000L);
        target.setScopeId(0L);
        target.setSource(GitHubAccessTarget.Source.REQUEST);
        var provider = new IdentityProvider();
        provider.setId(1L);
        team.setId(30L);
        team.setNativeId(40L);
        team.setProvider(provider);
        team.setOrganization("acme");
        when(providers.findProviderId("GITHUB", "https://github.com")).thenReturn(Optional.of(1L));
        when(scopes.resolve(workspace)).thenReturn(Optional.of(new WorkspaceTeamScope("acme", 1L)));
        when(approvals.currentApprovals(2L, now))
                .thenReturn(List.of(new WorkspaceApprovedAccessQuery.Approval(
                        10L, 20L, now.plusSeconds(3600), List.of(30L), List.of(50L, 51L))));
        when(identities.accounts(Set.of(20L)))
                .thenReturn(Map.of(20L, new AccountIdentityQuery.AccountView(20L, "Developer", true)));
        when(identities.activeLinksForAccount(20L))
                .thenReturn(List.of(
                        github,
                        new AccountIdentityQuery.IdentityLinkView(
                                51L, 3L, "institutional-subject", null, null, null, null, null, null)));
        when(teams.findByNativeIdAndProviderId(40L, 1L)).thenReturn(Optional.of(team));
    }

    @Test
    void shouldBindTheApprovedRequestAndExpiryWithoutAnInstitutionalEligibilitySource() {
        assertThat(evidence.read(target).candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.githubUserId()).isEqualTo(61L);
            assertThat(candidate.requestId()).isEqualTo(10L);
            assertThat(candidate.expiresAt()).isEqualTo(now.plusSeconds(3600));
            assertThat(candidate.directoryIdentityLinkId()).isNull();
            assertThat(candidate.directorySubject()).isNull();
        });
    }

    @Test
    void shouldGrantOnlyTheApprovedTeamOnTheExactWorkspaceProvider() {
        target.setScopeId(40L);
        evidence.bindScope(target, 1000L, 40L);
        assertThat(evidence.read(target).candidates()).hasSize(1);
        target.setScopeId(41L);
        target.setRequestTeamId(31L);
        assertThat(evidence.read(target).candidates()).isEmpty();
        target.setScopeId(40L);
        team.setOrganization("another-workspace");
        assertThatThrownBy(() -> evidence.bindScope(target, 1000L, 40L)).isInstanceOf(IllegalArgumentException.class);
        team.setOrganization("acme");
        team.getProvider().setId(99L);
        assertThatThrownBy(() -> evidence.bindScope(target, 1000L, 40L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepThePinnedApprovalWhenTheTeamMirrorIsUnavailable() {
        target.setScopeId(40L);
        evidence.bindScope(target, 1000L, 40L);
        target.setAuthorityHeld(true);
        when(teams.findByNativeIdAndProviderId(40L, 1L)).thenReturn(Optional.empty());
        evidence.bindScope(target, 1000L, 40L);
        assertThat(evidence.read(target).candidates()).hasSize(1);
        assertThat(target.getRequestTeamId()).isEqualTo(30L);
    }

    @Test
    void shouldWithholdEligibilityWhenAnyOriginallyRequiredIdentityWasUnlinked() {
        when(identities.activeLinksForAccount(20L)).thenReturn(List.of(github));
        assertThat(evidence.read(target).candidates()).isEmpty();
    }

    @Test
    void shouldRejectAnotherOrganizationEvenWithAValidAccessAppAuthorization() {
        assertThatThrownBy(() -> evidence.requireOrganization(target, 1001L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own GitHub organization");
        assertThatThrownBy(() -> evidence.configure(target, Set.of("directory-group")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not directory groups");
    }
}
