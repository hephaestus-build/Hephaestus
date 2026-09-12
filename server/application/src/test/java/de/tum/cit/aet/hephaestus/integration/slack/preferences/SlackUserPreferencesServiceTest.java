package de.tum.cit.aet.hephaestus.integration.slack.preferences;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.web.server.ResponseStatusException;

class SlackUserPreferencesServiceTest extends BaseUnitTest {

    private static final long ACCOUNT_ID = 10L;
    private static final long SLACK_PROVIDER_ID = 99L;

    @Mock
    private AccountIdentityQuery accountIdentityQuery;

    @Mock
    private AccountWorkspaceMembershipQuery membershipQuery;

    @Mock
    private GitProviderRegistry gitProviderRegistry;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private SlackParticipantConsentRepository participantConsentRepository;

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private SlackParticipantConsentService participantConsentService;

    @Mock
    private SlackPersonErasureService erasureService;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private WorkspaceSummaryQuery workspaceSummaryQuery;

    private SlackUserPreferencesService service;

    @BeforeEach
    void setUp() {
        service = new SlackUserPreferencesService(
                accountIdentityQuery,
                membershipQuery,
                gitProviderRegistry,
                connectionRepository,
                participantConsentRepository,
                monitoredChannelRepository,
                participantConsentService,
                erasureService,
                identityResolver,
                workspaceSummaryQuery);
        when(gitProviderRegistry.resolveProviderId("SLACK", SlackUserPreferencesService.SLACK_SERVER_URL))
                .thenReturn(SLACK_PROVIDER_ID);
    }

    @Test
    void listReturnsOnlyAccessibleSlackWorkspacesWithConsentState() {
        givenWorkspaceSummary();
        var slackLink = link(SLACK_PROVIDER_ID, "U1", "T1", "Felix Slack");
        var gitLabLink = link(7L, "42", null, "ga84xah");
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID)).thenReturn(List.of(slackLink, gitLabLink));
        when(membershipQuery.membershipsForAccount(ACCOUNT_ID))
                .thenReturn(List.of(new AccountWorkspaceMembershipQuery.WorkspaceMembershipView(
                        1L, "acme", "Hephaestus", "MEMBER", 314L)));
        Connection visible = slackConnection(1L, "acme", "Hephaestus", "T1", "acme-slack");
        Connection inaccessible = slackConnection(2L, "other", "Other", "T1", "acme-slack");
        when(connectionRepository.findAllByKindAndInstanceKeyInAndState(
                        IntegrationKind.SLACK, Set.of("T1"), IntegrationState.ACTIVE))
                .thenReturn(List.of(visible, inaccessible));
        when(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(1L, "U1"))
                .thenReturn(true);
        when(monitoredChannelRepository.countByWorkspaceIdAndConsentState(1L, ConsentState.ACTIVE))
                .thenReturn(3L);

        SlackUserPreferencesDTO dto = service.listForAccount(ACCOUNT_ID);

        assertThat(dto.workspaces()).singleElement().satisfies(workspace -> {
            assertThat(workspace.workspaceSlug()).isEqualTo("acme");
            assertThat(workspace.workspaceName()).isEqualTo("Hephaestus");
            assertThat(workspace.slackTeamId()).isEqualTo("T1");
            assertThat(workspace.slackTeamName()).isEqualTo("acme-slack");
            assertThat(workspace.slackDisplayName()).isEqualTo("Felix Slack");
            assertThat(workspace.channelMessagesAllowed()).isFalse();
            assertThat(workspace.activeMonitoredChannelCount()).isEqualTo(3L);
        });
    }

    @Test
    void updateOptOutRecordsDecisionAndErasesSlackPerson() {
        givenWorkspaceSummary();
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        1L, IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(slackConnection(1L, "acme", "Hephaestus", "T1", "acme-slack")));
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID))
                .thenReturn(List.of(link(SLACK_PROVIDER_ID, "U1", "T1", "Felix Slack")));
        when(identityResolver.resolveMemberId(1L, "T1", "U1")).thenReturn(Optional.of(123L));
        when(monitoredChannelRepository.countByWorkspaceIdAndConsentState(1L, ConsentState.ACTIVE))
                .thenReturn(1L);

        SlackUserWorkspacePreferencesDTO dto = service.updateChannelMessagesAllowed(1L, ACCOUNT_ID, false);

        verify(participantConsentService).recordChannelMessageOptOut(1L, "U1");
        verify(erasureService).erasePerson(1L, 123L, "U1");
        assertThat(dto.channelMessagesAllowed()).isFalse();
    }

    @Test
    void updateOptInDoesNotEraseData() {
        givenWorkspaceSummary();
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        1L, IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(slackConnection(1L, "acme", "Hephaestus", "T1", "acme-slack")));
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID))
                .thenReturn(List.of(link(SLACK_PROVIDER_ID, "U1", "T1", "Felix Slack")));

        service.updateChannelMessagesAllowed(1L, ACCOUNT_ID, true);

        verify(participantConsentService).recordChannelMessageOptIn(1L, "U1");
        // Opt-in must not erase anyone, not merely the current person.
        verify(erasureService, never()).erasePerson(anyLong(), anyLong(), anyString());
    }

    @Test
    void updateRejectsWorkspaceWithoutCurrentAccountSlackLink() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        1L, IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(slackConnection(1L, "acme", "Hephaestus", "T1", "acme-slack")));
        when(accountIdentityQuery.activeLinksForAccount(ACCOUNT_ID))
                .thenReturn(List.of(link(SLACK_PROVIDER_ID, "U2", "T2", "Other Slack")));

        assertThatThrownBy(() -> service.updateChannelMessagesAllowed(1L, ACCOUNT_ID, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Slack account is not linked");
    }

    private static AccountIdentityQuery.IdentityLinkView link(
            long providerId, String subject, @Nullable String teamId, String username) {
        return new AccountIdentityQuery.IdentityLinkView(
                1L, providerId, subject, username, username, null, null, null, teamId);
    }

    private void givenWorkspaceSummary() {
        when(workspaceSummaryQuery.findById(1L))
                .thenReturn(Optional.of(new WorkspaceSummaryQuery.WorkspaceSummary(1L, "acme", "Hephaestus")));
    }

    private static Connection slackConnection(
            long workspaceId, String workspaceSlug, String workspaceName, String teamId, String teamName) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setWorkspaceSlug(workspaceSlug);
        workspace.setDisplayName(workspaceName);
        Connection connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                teamId,
                new ConnectionConfig.SlackConfig(teamId, teamName, null, null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connection.setDisplayName(teamName);
        return connection;
    }
}
