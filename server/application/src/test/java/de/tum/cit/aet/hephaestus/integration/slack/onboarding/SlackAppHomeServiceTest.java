package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery.PreferencesView;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import tools.jackson.databind.json.JsonMapper;

class SlackAppHomeServiceTest extends BaseUnitTest {

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private AccountPreferencesQuery preferencesQuery;

    @Mock
    private SlackParticipantConsentRepository participantConsentRepository;

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private MentorReadinessQuery mentorReadinessQuery;

    @Mock
    private SlackMessageService messageService;

    @Mock
    private SlackOnboardingService onboardingService;

    @Mock
    private SlackHephaestusUiLinks uiLinks;

    private SlackAppHomeService service;

    @BeforeEach
    void setUp() {
        service = new SlackAppHomeService(
                workspaceResolver,
                identityResolver,
                preferencesQuery,
                participantConsentRepository,
                monitoredChannelRepository,
                mentorReadinessQuery,
                messageService,
                onboardingService,
                uiLinks);
        Mockito.lenient().when(mentorReadinessQuery.isReady(7L)).thenReturn(true);
        Mockito.lenient()
                .when(monitoredChannelRepository.countByWorkspaceIdAndConsentState(7L, ConsentState.ACTIVE))
                .thenReturn(1L);
        Mockito.lenient().when(uiLinks.workspaceHomeUrl(7L)).thenReturn("https://heph.example/w/team");
        Mockito.lenient().when(uiLinks.userSettingsUrl()).thenReturn("https://heph.example/settings");
    }

    @Test
    void linkedParticipatingMember_rendersDisclosureAndOptOutToggle_noQuietHours() throws Exception {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(preferencesQuery.preferencesForUserId(314L)).thenReturn(Optional.of(new PreferencesView(true, true)));

        View view = service.buildHomeView(7L, "T1", "U1");

        assertThat(view.getType()).isEqualTo("home");
        String rendered = view.getBlocks().toString();
        String json = JsonMapper.builder().build().writeValueAsString(view);
        assertThat(rendered).contains("https://heph.example/settings");
        assertThat(rendered).doesNotContain("https://heph.example/w/team");
        assertThat(rendered).doesNotContain("Try asking in Messages");
        assertThat(rendered).doesNotContain("What should I improve in my latest PR");
        assertThat(rendered).doesNotContain("How can I write a clearer review");
        assertThat(rendered).doesNotContain("What project-practice issue should I follow up on");
        assertThat(json).doesNotContain("\\\\n");
        assertThat(rendered).contains("Ready to answer"); // mentor-status anchor
        assertThat(rendered).contains("Linked as `octocat`"); // identity anchor
        assertThat(rendered).contains("Allowed, 1 active channel"); // channel-count anchor
        assertThat(rendered).contains("Stop using my messages"); // opt-out wording
        assertThat(rendered).contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        assertThat(rendered).contains(SlackAppHomeService.ACTION_RESEARCH_OPT_OUT); // participating → offer opt-out
        assertThat(rendered).doesNotContain(SlackAppHomeService.ACTION_RESEARCH_OPT_IN);
        // The unwired quiet-hours control must not reach users until its write path exists.
        assertThat(rendered).doesNotContain("open_quiet_hours");
        assertThat(rendered).doesNotContain("Quiet hours");
    }

    @Test
    void linkedNonParticipatingMember_rendersOptInToggle() {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(preferencesQuery.preferencesForUserId(314L)).thenReturn(Optional.of(new PreferencesView(false, true)));

        View view = service.buildHomeView(7L, "T1", "U1");

        String rendered = view.getBlocks().toString();
        assertThat(rendered).contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        assertThat(rendered).contains(SlackAppHomeService.ACTION_RESEARCH_OPT_IN);
        assertThat(rendered).doesNotContain(SlackAppHomeService.ACTION_RESEARCH_OPT_OUT);
    }

    @Test
    void unlinkedMember_showsMessageControlAndLinkCta_noResearchToggle() {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.empty());
        List<LayoutBlock> cta = List.of(section(s -> s.text(markdownText("LINK_ME_MARKER"))));
        when(onboardingService.linkCtaBlocks()).thenReturn(cta);

        View view = service.buildHomeView(7L, "T1", "U1");

        String rendered = view.getBlocks().toString();
        assertThat(rendered)
                .contains("LINK_ME_MARKER", "Check account access", "No active linked workspace member")
                .doesNotContain("Not linked");
        assertThat(rendered).contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        assertThat(rendered).doesNotContain(SlackAppHomeService.ACTION_RESEARCH_OPT_OUT);
        assertThat(rendered).doesNotContain(SlackAppHomeService.ACTION_RESEARCH_OPT_IN);
        verifyNoInteractions(preferencesQuery); // no identity → no preference read
    }

    @Test
    void optedOutMember_rendersChannelMessageOptIn() {
        when(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(7L, "U1"))
                .thenReturn(true);
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.empty());
        when(onboardingService.linkCtaBlocks()).thenReturn(List.of(section(s -> s.text(markdownText("LINK")))));

        View view = service.buildHomeView(7L, "T1", "U1");

        String rendered = view.getBlocks().toString();
        assertThat(rendered).contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN);
        assertThat(rendered).contains("Allow future messages"); // opt-in wording, symmetric to the opt-out anchor
        assertThat(rendered).doesNotContain(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
    }

    @Test
    void mentorNotReady_rendersUnavailableStatus() {
        when(mentorReadinessQuery.isReady(7L)).thenReturn(false);
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(preferencesQuery.preferencesForUserId(314L)).thenReturn(Optional.of(new PreferencesView(true, true)));

        View view = service.buildHomeView(7L, "T1", "U1");

        assertThat(view.getBlocks().toString()).contains("Unavailable").contains("Mentor unavailable");
    }

    @Test
    void onHomeOpened_unknownTeam_doesNotPublish() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.empty());

        service.onHomeOpened("T1", "U1");

        verify(messageService, never()).publishHomeView(anyLong(), any(), any());
    }

    @Test
    void onHomeOpened_linkedMember_publishesHomeView() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(preferencesQuery.preferencesForUserId(314L)).thenReturn(Optional.of(new PreferencesView(true, true)));

        service.onHomeOpened("T1", "U1");

        verify(messageService).publishHomeView(eq(7L), eq("U1"), any(View.class));
    }

    @Test
    void blankInput_doesNothing() {
        service.onHomeOpened("", "U1");
        service.onHomeOpened("T1", "");

        verifyNoInteractions(workspaceResolver, messageService);
    }

    private static User developer() {
        var user = new User();
        user.setId(314L);
        user.setLogin("octocat");
        return user;
    }
}
