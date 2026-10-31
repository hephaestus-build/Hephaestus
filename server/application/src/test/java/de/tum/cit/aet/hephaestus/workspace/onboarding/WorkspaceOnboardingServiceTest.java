package de.tum.cit.aet.hephaestus.workspace.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiReadiness;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkspaceOnboardingServiceTest extends BaseUnitTest {
    @Mock
    private WorkspaceRepository workspaces;

    @Mock
    private WorkspaceOnboardingSettingsRepository settings;

    @Mock
    private MembershipRepository members;

    @Mock
    private AccountDirectory choices;

    @Mock
    private AccountMembershipQuery memberships;

    @Mock
    private WorkspaceOnboardingLinks links;

    @Mock
    private WorkspaceAiAvailability availability;

    @Mock
    private MemberAiReadiness aiReadiness;

    @Mock
    private ConfigAuditPort audit;

    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");
    private final Map<Long, Account> savedChoices = new HashMap<>();
    private static final WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO OPEN_SLACK =
            new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(
                    9L, "Slack", "SLACK", "slack", "Team", true, true, false);
    private static final WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO UNAVAILABLE_SLACK =
            new WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO(9L, "Slack", "SLACK", null, null, true, false, false);
    private final WorkspaceContext context =
            new WorkspaceContext(1L, "engineering", "Engineering", null, null, Set.of());
    private WorkspaceOnboardingService service;

    @BeforeEach
    void setUp() {
        service = new WorkspaceOnboardingService(
                workspaces, settings, members, choices, memberships, links, availability, aiReadiness, audit);
        lenient().when(choices.find(anyLong())).thenAnswer(invocation -> {
            var row = savedChoices.get(invocation.<Long>getArgument(0));
            return row == null
                    ? Optional.empty()
                    : Optional.of(new AccountDirectory.AccountView(
                            Objects.requireNonNull(row.getId()),
                            row.getStatus(),
                            row.getDisplayName(),
                            row.getAiChoice(),
                            row.getAiChoiceAt(),
                            row.isFeedbackDeliveryEnabled()));
        });
        lenient()
                .doAnswer(invocation -> {
                    long id = invocation.getArgument(0);
                    var row = savedChoices.computeIfAbsent(id, key -> {
                        var account = new Account("Developer");
                        account.setId(key);
                        return account;
                    });
                    row.setAiChoice(invocation.getArgument(1));
                    row.setAiChoiceAt(NOW);
                    return null;
                })
                .when(choices)
                .setAiChoice(anyLong(), any());
    }

    private Workspace member() {
        var account = new Account("Onboarding developer");
        account.setId(10L);
        savedChoices.putIfAbsent(10L, account);
        var workspace = new Workspace();
        workspace.setId(1L);
        lenient().when(workspaces.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        when(memberships.membershipsForAccount(10L))
                .thenReturn(List.of(new AccountMembershipQuery.MembershipView(
                        1L, "engineering", "Engineering", AccountMembershipQuery.Role.MEMBER)));
        var membership = new Membership();
        membership.setWorkspace(workspace);
        lenient().when(members.findByWorkspace_IdAndId_AccountId(1L, 10L)).thenReturn(Optional.of(membership));
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
    void shouldStillAskForAiSetupAfterThePublicStepAtRevisionZero() {
        member();
        var policy = enabledPolicy();
        policy.setRevision(0);
        var publicStep = new Membership();

        publicStep.setPublicActivitySeen(true);
        when(members.findByWorkspace_IdAndId_AccountId(1L, 10L)).thenReturn(Optional.of(publicStep));
        when(links.options(1L, 10L, List.of())).thenReturn(List.of(OPEN_SLACK));
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
        chose(AiChoice.NO_AI);
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
        publicStep.setOnboardingSeenRevision(0L);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
    }

    private Account chose(AiChoice choice) {
        var row = new Account("Choice developer");
        row.setId(10L);
        row.setAiChoice(choice);
        row.setAiChoiceAt(NOW.minusSeconds(60));
        savedChoices.put(10L, row);
        return row;
    }

    private Membership seen(Workspace workspace, long revision) {
        var row = new Membership();
        row.setWorkspace(workspace);
        row.setOnboardingSeenRevision(revision);
        when(members.findByWorkspace_IdAndId_AccountId(1L, 10L)).thenReturn(Optional.of(row));
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
        verify(choices, never()).setAiChoice(anyLong(), any());
    }

    @Test
    void shouldSaveTheChoiceOnTheAccountEvenWhenRequiredAccountIsUnavailable() {
        member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(UNAVAILABLE_SLACK));
        var result = service.choose(context, 10L, AiChoice.NO_AI);
        verify(choices).setAiChoice(10L, AiChoice.NO_AI);
        var savedAccount = Objects.requireNonNull(savedChoices.get(10L));
        assertThat(savedAccount.getAiChoiceAt()).isEqualTo(NOW);
        // The page reads the answer back the same way every other workspace will.
        assertThat(result.aiChoice()).isEqualTo(AiChoice.NO_AI);
        verify(members, never()).save(any());
    }

    @Test
    void shouldAnswerTheAccountEndpointWithoutWorkspaceModelInventory() {
        var unanswered = service.accountChoice(10L);
        assertThat(unanswered.choice()).isNull();
        assertThat(unanswered.updatedAt()).isNull();
        var result = service.chooseForAccount(10L, AiChoice.CLOUD);
        assertThat(result.choice()).isEqualTo(AiChoice.CLOUD);
        assertThat(result.updatedAt()).isEqualTo(NOW);
        verifyNoInteractions(availability, settings, members);
    }

    @Test
    void shouldNotTreatSkipAsAnAnswer() {
        var workspace = member();
        enabledPolicy();
        var result = service.dismiss(context, 10L);
        var saved = ArgumentCaptor.forClass(Membership.class);
        verify(members).save(saved.capture());
        assertThat(saved.getValue().getOnboardingSeenRevision()).isEqualTo(3);
        assertThat(saved.getValue().getWorkspace()).isSameAs(workspace);
        verify(choices, never()).setAiChoice(anyLong(), any());
        assertThat(result.aiChoice()).isNull();
    }

    @Test
    void shouldAcceptAChoiceNoBindingCoversYet() {
        member();
        enabledPolicy();
        when(availability.options(1L))
                .thenReturn(List.of(
                        new WorkspaceAiAvailability.Option(AiChoice.IN_HOUSE_ONLY, false, false, List.of()),
                        new WorkspaceAiAvailability.Option(AiChoice.CLOUD, true, true, List.of())));
        var result = service.choose(context, 10L, AiChoice.IN_HOUSE_ONLY);
        verify(choices).setAiChoice(10L, AiChoice.IN_HOUSE_ONLY);
        assertThat(result.aiOptions())
                .filteredOn(option -> option.choice() == AiChoice.IN_HOUSE_ONLY)
                .allSatisfy(option -> {
                    assertThat(option.practiceReviewsReady()).isFalse();
                    assertThat(option.mentorReady()).isFalse();
                });
    }

    @Test
    void shouldNotAskAgainInAnotherWorkspaceOnceTheAccountHasAnswered() {
        member();
        enabledPolicy();
        chose(AiChoice.CLOUD);
        var result = service.state(context, 10L);
        assertThat(result.needsSetup()).isFalse();
        assertThat(result.aiChoice()).isEqualTo(AiChoice.CLOUD);
        assertThat(result.aiChoiceRequired()).isTrue();
        verify(members, never()).save(any());
    }

    @Test
    void shouldStillAskForARequiredAccountLinkWhenTheChoiceIsAlreadyMade() {
        member();
        var policy = enabledPolicy();
        policy.setRequiredConnectionIds(List.of(9L));
        chose(AiChoice.CLOUD);
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
        chose(AiChoice.CLOUD);
        when(links.options(1L, 10L, List.of(9L))).thenReturn(List.of(OPEN_SLACK));
        var row = seen(workspace, 3);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
        row.setOnboardingSeenRevision(2L);
        assertThat(service.state(context, 10L).needsSetup()).isTrue();
    }

    @Test
    void shouldReturnAfterASkipWithoutAnAnswerOnlyOnceTheOwnerChangedTheSetup() {
        var workspace = member();
        enabledPolicy();
        var row = seen(workspace, 3);
        assertThat(service.state(context, 10L).needsSetup()).isFalse();
        row.setOnboardingSeenRevision(1L);
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
}
