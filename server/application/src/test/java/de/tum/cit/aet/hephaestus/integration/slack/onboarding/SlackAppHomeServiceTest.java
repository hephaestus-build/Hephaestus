package de.tum.cit.aet.hephaestus.integration.slack.onboarding;

import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.view.View;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorRefusal;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorTurnRunner;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class SlackAppHomeServiceTest extends BaseUnitTest {

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private SlackParticipantConsentRepository participantConsentRepository;

    @Mock
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Mock
    private MentorReadinessQuery mentorReadinessQuery;

    @Mock
    private MentorTurnRunner mentorTurnRunner;

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
                participantConsentRepository,
                monitoredChannelRepository,
                mentorReadinessQuery,
                mentorTurnRunner,
                messageService,
                onboardingService,
                uiLinks);
        lenient().when(mentorReadinessQuery.isReady(7L)).thenReturn(true);
        lenient()
                .when(monitoredChannelRepository.countByWorkspaceIdAndConsentState(7L, ConsentState.ACTIVE))
                .thenReturn(1L);
        lenient().when(uiLinks.userSettingsUrl()).thenReturn("https://heph.example/settings");
    }

    @Test
    void linkedMember_rendersMessageControlsAndAuthoritativeResearchSettingsLink() throws Exception {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));

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
        assertThat(rendered).contains("Enabled for you"); // mentor-status anchor
        assertThat(rendered).contains("Linked as `octocat`"); // identity anchor
        assertThat(rendered).contains("Allowed, 1 active channel"); // channel-count anchor
        assertThat(rendered).contains("Stop using my messages"); // opt-out wording
        assertThat(rendered).contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        assertThat(rendered).contains("research participation", "Open account settings");
        assertThat(rendered).doesNotContain("research_opt_out", "research_opt_in", "Research use is", "*Research use*");
        // The unwired quiet-hours control must not reach users until its write path exists.
        assertThat(rendered).doesNotContain("open_quiet_hours");
        assertThat(rendered).doesNotContain("Quiet hours");
    }

    @ParameterizedTest
    @EnumSource(MentorRefusal.class)
    void shouldRenderMemberRefusalWithoutInvitation(MentorRefusal refusal) {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(mentorTurnRunner.refusal(7L, 314L)).thenReturn(Optional.of(refusal));

        String rendered = service.buildHomeView(7L, "T1", "U1").getBlocks().toString();

        assertThat(rendered)
                .contains(
                        refusal.userMessage(),
                        "Linked as `octocat`",
                        "Open account settings",
                        "Allowed, 1 active channel")
                .contains(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT)
                .doesNotContain("Enabled for you", "Ask in the Messages tab");
        assertThat(rendered)
                .contains(
                        switch (refusal) {
                            case PERSON_ERASED -> "Personal data erased";
                            case NO_AI -> "Off for you";
                            case CHOICE_REQUIRED -> "Choose your AI";
                            case UNAVAILABLE -> "Unavailable for your AI choice";
                        });
    }

    @Test
    void shouldRestoreInvitationWhenMemberBecomesEligible() {
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(mentorTurnRunner.refusal(7L, 314L))
                .thenReturn(Optional.of(MentorRefusal.NO_AI))
                .thenReturn(Optional.empty());

        String declined = service.buildHomeView(7L, "T1", "U1").getBlocks().toString();
        String eligible = service.buildHomeView(7L, "T1", "U1").getBlocks().toString();

        assertThat(declined).contains(MentorRefusal.NO_AI.userMessage()).doesNotContain("Ask in the Messages tab");
        assertThat(eligible)
                .contains("Enabled for you", "Ask in the Messages tab")
                .doesNotContain(MentorRefusal.NO_AI.userMessage());
    }

    @Test
    void shouldNotPublishInvitationWhenMemberReadinessFails() {
        when(workspaceResolver.resolveWorkspaceId("T1")).thenReturn(Optional.of(7L));
        when(identityResolver.resolveDeveloper(7L, "T1", "U1")).thenReturn(Optional.of(developer()));
        when(mentorTurnRunner.refusal(7L, 314L)).thenThrow(new IllegalStateException("Admission unavailable"));

        assertThatThrownBy(() -> service.onHomeOpened("T1", "U1")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(messageService);
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
        assertThat(rendered).doesNotContain("research_opt_out");
        assertThat(rendered).doesNotContain("research_opt_in");
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
