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
import java.util.HashMap;
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
    private AccountAiChoiceRepository choices;

    @Mock
    private AccountWorkspaceMembershipQuery memberships;

    @Mock
    private WorkspaceOnboardingLinks links;

    @Mock
    private WorkspaceAiAvailability availability;

    @Mock
    private ConfigAuditPort audit;

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private final Map<Long, AccountAiChoice> savedChoices = new HashMap<>();
    private static final WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO OPEN_SLACK =
            new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                    9L, "Slack", "SLACK", "slack", "Team", true, true, false);
    private static final WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO UNAVAILABLE_SLACK =
            new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(9L, "Slack", "SLACK", null, null, true, false, false);
    private final WorkspaceContext context =
            new WorkspaceContext(1L, "engineering", "Engineering", null, null, true, true, Set.of());
    private WorkspaceOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceOnboardingService(
                workspaces,
                settings,
                members,
                choices,
                memberships,
                links,
                availability,
                audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // The account row is read back by the same method that wrote it, so the mock keeps what it saved.
        lenient().when(choices.save(any())).thenAnswer(invocation -> {
            AccountAiChoice row = invocation.getArgument(0);
            savedChoices.put(row.getAccountId(), row);
            return row;
        });
        lenient()
                .when(choices.findById(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(savedChoices.get(invocation.<Long>getArgument(0))));
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

    private AccountAiChoice chose(MemberAiChoice choice) {
        var row = new AccountAiChoice();
        row.setAccountId(10L);
        row.setAiChoice(choice);
        row.setUpdatedAt(NOW.minusSeconds(60));
        savedChoices.put(10L, row);
        return row;
    }

    private WorkspaceMemberOnboarding seen(Workspace workspace, long revision) {
        var row = new WorkspaceMemberOnboarding();
        row.setWorkspace(workspace);
        row.setAccountId(10L);
        row.setSeenRevision(revision);
        when(members.findByWorkspace_IdAndAccountId(1L, 10L)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void shouldRejectPublicReadersWithoutActualMembership() {
        assertThatThrownBy(() -> service.state(context, 10L))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(settings, members, choices, links, availability);
    }

    @Test
    void shouldShowFirstVisitWithoutPreselectingAi() {
        member();
        enabledPolicy();
        var result = service.state(context, 10L);
        assertThat(result.needsSetup()).isTrue();
        assertThat(result.aiChoice()).isNull();
        verify(members, never()).save(any());
        verify(choices, never()).save(any());
    }

    @Test
    void shouldSaveTheChoiceOnTheAccountEvenWhenRequiredAccountIsUnavailable() {
        member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(UNAVAILABLE_SLACK));
        var result = service.choose(context, 10L, MemberAiChoice.NO_AI);
        var saved = ArgumentCaptor.forClass(AccountAiChoice.class);
        verify(choices).save(saved.capture());
        assertThat(saved.getValue().getAiChoice()).isEqualTo(MemberAiChoice.NO_AI);
        assertThat(saved.getValue().getAccountId()).isEqualTo(10L);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        // The page reads the answer back the same way every other workspace will.
        assertThat(result.aiChoice()).isEqualTo(MemberAiChoice.NO_AI);
        verify(members, never()).save(any());
    }

    @Test
    void shouldAnswerTheAccountEndpointWithoutAWorkspace() {
        assertThat(service.accountChoice(10L)).isEqualTo(new AccountAiChoiceDTO(null, null));
        var result = service.chooseForAccount(10L, MemberAiChoice.CLOUD);
        assertThat(result.choice()).isEqualTo(MemberAiChoice.CLOUD);
        assertThat(result.updatedAt()).isEqualTo(NOW);
        verifyNoInteractions(memberships, settings, members);
    }

    @Test
    void shouldNotTreatSkipAsAnAnswer() {
        var workspace = member();
        enabledPolicy();
        var result = service.dismiss(context, 10L);
        var saved = ArgumentCaptor.forClass(WorkspaceMemberOnboarding.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getSeenRevision()).isEqualTo(3);
        assertThat(saved.getValue().getWorkspace()).isSameAs(workspace);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        verify(choices, never()).save(any());
        assertThat(result.aiChoice()).isNull();
    }

    @Test
    void shouldAcceptAChoiceNoBindingCoversYet() {
        member();
        enabledPolicy();
        when(availability.options(1L))
                .thenReturn(List.of(
                        new WorkspaceAiAvailability.Option(MemberAiChoice.IN_HOUSE_ONLY, false, false),
                        new WorkspaceAiAvailability.Option(MemberAiChoice.CLOUD, true, true)));
        var result = service.choose(context, 10L, MemberAiChoice.IN_HOUSE_ONLY);
        var saved = ArgumentCaptor.forClass(AccountAiChoice.class);
        verify(choices).save(saved.capture());
        assertThat(saved.getValue().getAiChoice()).isEqualTo(MemberAiChoice.IN_HOUSE_ONLY);
        assertThat(result.aiOptions())
                .filteredOn(option -> option.choice() == MemberAiChoice.IN_HOUSE_ONLY)
                .allSatisfy(option -> {
                    assertThat(option.practiceReviewsReady()).isFalse();
                    assertThat(option.mentorReady()).isFalse();
                });
    }

    @Test
    void shouldNotAskAgainInAnotherWorkspaceOnceTheAccountHasAnswered() {
        member();
        enabledPolicy();
        chose(MemberAiChoice.CLOUD);
        var result = service.state(context, 10L);
        assertThat(result.needsSetup()).isFalse();
        assertThat(result.aiChoice()).isEqualTo(MemberAiChoice.CLOUD);
        assertThat(result.aiChoiceRequired()).isTrue();
        verify(members, never()).save(any());
    }

    @Test
    void shouldStillAskForARequiredAccountLinkWhenTheChoiceIsAlreadyMade() {
        member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        chose(MemberAiChoice.CLOUD);
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(OPEN_SLACK));
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(UNAVAILABLE_SLACK));
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
    }

    @Test
    void shouldReturnAfterASkipOnlyOnceTheOwnerChangedTheSetup() {
        var workspace = member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        chose(MemberAiChoice.CLOUD);
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(OPEN_SLACK));
        var row = seen(workspace, 3);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
        row.setSeenRevision(2);
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
    }

    @Test
    void shouldReturnAfterASkipWithoutAnAnswerOnlyOnceTheOwnerChangedTheSetup() {
        var workspace = member();
        enabledPolicy();
        var row = seen(workspace, 3);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
        row.setSeenRevision(1);
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
    }

    @Test
    void shouldNeverShowTheSetupPageWhenTheOwnerTurnedItOff() {
        member();
        var policy = enabledPolicy();
        policy.setEnabled(false);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
        service.dismiss(context, 10L);
        verify(members, never()).save(any());
    }

    @Test
    void shouldKeepAiChoiceRequiredAfterHidingTheSetupPage() {
        var workspace = new Workspace();
        workspace.setId(1L);
        when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        var policy = enabledPolicy();
        // The request says false; the latch wins and the response reports it.
        var result = service.configure(context, 10L, new WorkspaceOnboardingSettingsDTO(false, false, 3, List.of()));
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.isAiChoiceRequired()).isTrue();
        assertThat(result.aiChoiceRequired()).isTrue();
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
        assertThatThrownBy(() -> service.choose(context, 10L, MemberAiChoice.IN_HOUSE_ONLY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("account owner");
        assertThatThrownBy(() -> service.chooseForAccount(10L, MemberAiChoice.IN_HOUSE_ONLY))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("account owner");
        assertThatThrownBy(() -> service.dismiss(context, 10L)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(members, choices, workspaces);
    }
}
