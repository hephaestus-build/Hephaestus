package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceOnboardingServiceTest extends BaseUnitTest {
    @Mock
    private WorkspaceRepository workspaces;

    @Mock
    private WorkspaceOnboardingSettingsRepository settings;

    @Mock
    private WorkspaceMemberOnboardingRepository members;

    @Mock
    private AccountWorkspaceMembershipQuery memberships;

    @Mock
    private WorkspaceOnboardingLinks links;

    @Mock
    private WorkspaceAiAvailability availability;

    @Mock
    private ConfigAuditPort audit;

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private final WorkspaceContext context =
            new WorkspaceContext(1L, "engineering", "Engineering", null, null, true, true, Set.of());
    private WorkspaceOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceOnboardingService(
                workspaces,
                settings,
                members,
                memberships,
                links,
                availability,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private Workspace member() {
        var workspace = new Workspace();
        workspace.setId(1L);
        lenient().when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(memberships.membershipsForAccount(10L))
                .thenReturn(List.of(new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                        1L, "engineering", "Engineering", "MEMBER", 20L)));
        return workspace;
    }

    private WorkspaceOnboardingSettings enabledPolicy() {
        var policy = new WorkspaceOnboardingSettings();
        policy.setWorkspaceId(1L);
        policy.setEnabled(true);
        policy.setAiChoiceRequired(true);
        policy.setRevision(3);
        when(settings.findByWorkspaceId(1L)).thenReturn(Optional.of(policy));
        return policy;
    }

    @Test
    void shouldRejectPublicReadersWithoutActualMembership() {
        assertThatThrownBy(() -> service.state(context, 10L))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(settings, members, links, availability);
    }

    @Test
    void shouldShowFirstVisitWithoutPreselectingAi() {
        member();
        enabledPolicy();
        var result = service.state(context, 10L);
        assertThat(result.needsWelcome()).isTrue();
        assertThat(result.aiChoice()).isNull();
        assertThat(result.completed()).isFalse();
        verify(members, never()).save(any());
    }

    @Test
    void shouldSaveNoAiEvenWhenRequiredAccountIsUnavailable() {
        member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        when(links.options(1L, 10L, List.of(9L)))
                .thenReturn(List.of(new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                        9L, "Slack", "SLACK", null, null, true, false, false)));
        service.choose(context, 10L, MemberAiChoice.NO_AI);
        var saved = ArgumentCaptor.forClass(WorkspaceMemberOnboarding.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getAiChoice()).isEqualTo(MemberAiChoice.NO_AI);
        assertThat(saved.getValue().getCompletedAt()).isNull();
        assertThat(saved.getValue().getAccountId()).isEqualTo(10L);
    }

    @Test
    void shouldNotTreatNotNowAsConsentOrCompletedSetup() {
        member();
        enabledPolicy();
        service.dismiss(context, 10L);
        var saved = ArgumentCaptor.forClass(WorkspaceMemberOnboarding.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getWelcomedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getAiChoice()).isNull();
        assertThat(saved.getValue().getCompletedAt()).isNull();
    }

    @Test
    void shouldRefuseUnavailableAiInsteadOfSwitchingLocations() {
        member();
        when(availability.options(1L))
                .thenReturn(List.of(new WorkspaceAiAvailability.Option(MemberAiChoice.PRIVATE_CLOUD, true, true)));
        assertThatThrownBy(() -> service.choose(context, 10L, MemberAiChoice.ON_PREMISES))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not available");
        verifyNoInteractions(members);
    }

    @Test
    void shouldRefuseCompletionWhenPolicyChanged() {
        member();
        enabledPolicy();
        assertThatThrownBy(() -> service.complete(context, 10L, 2))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("changed");
        verifyNoInteractions(members);
    }

    @Test
    void shouldRefuseCompletionUntilRequiredAccountIsLinked() {
        var workspace = member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        var row = new WorkspaceMemberOnboarding();
        row.setWorkspace(workspace);
        row.setAccountId(10L);
        row.setAiChoice(MemberAiChoice.NO_AI);
        when(members.findByWorkspace_IdAndAccountId(1L, 10L)).thenReturn(Optional.of(row));
        when(links.options(1L, 10L, List.of(9L)))
                .thenReturn(List.of(new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                        9L, "Slack", "SLACK", "slack", "Team", true, true, false)));
        assertThatThrownBy(() -> service.complete(context, 10L, 3))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required workspace accounts");
        assertThat(row.getCompletedAt()).isNull();
        verify(members, never()).save(any());
    }

    @Test
    void shouldFinishWithNoAiAndPersistWelcomeIndependently() {
        var workspace = member();
        enabledPolicy();
        var row = new WorkspaceMemberOnboarding();
        row.setWorkspace(workspace);
        row.setAccountId(10L);
        row.setAiChoice(MemberAiChoice.NO_AI);
        when(members.findByWorkspace_IdAndAccountId(1L, 10L)).thenReturn(Optional.of(row));
        var result = service.complete(context, 10L, 3);
        assertThat(row.getCompletedAt()).isEqualTo(NOW);
        assertThat(result.completed()).isTrue();
        assertThat(result.needsWelcome()).isFalse();
        assertThat(result.aiChoice()).isEqualTo(MemberAiChoice.NO_AI);
    }

    @Test
    void shouldKeepAiChoiceRequiredAfterHidingTheWelcome() {
        var workspace = new Workspace();
        workspace.setId(1L);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        var policy = enabledPolicy();
        service.configure(context, 10L, new WorkspaceOnboardingSettingsDTO(false, 3, "", List.of()));
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.isAiChoiceRequired()).isTrue();
        verify(audit).record(any());
    }

    @Test
    void shouldRejectChoicesAndDismissalWhileImpersonating() {
        var jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("10")
                .claim("act", Map.of("sub", "99"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        assertThatThrownBy(() -> service.choose(context, 10L, MemberAiChoice.ON_PREMISES))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("account owner");
        assertThatThrownBy(() -> service.dismiss(context, 10L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.complete(context, 10L, 0)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(members, workspaces);
    }
}
