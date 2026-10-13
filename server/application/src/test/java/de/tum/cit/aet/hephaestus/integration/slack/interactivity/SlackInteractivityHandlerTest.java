package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorPosts;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackWeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class SlackInteractivityHandlerTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long RATER_ID = 7L;
    private static final long ACCOUNT_ID = 11L;
    private static final String TEAM = "T1";
    private static final String USER = "U1";
    private static final String CHANNEL = "D9";
    private static final String MESSAGE_TS = "100.5";
    private static final String TRIGGER = "trig-123";
    private static final String ANSWER_ID = "0b6f7f6e-4b7e-4b55-9d1e-1f1e8f0c2a11";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackMentorIdentityResolver identityResolver;

    @Mock
    private SlackAppHomeService appHomeService;

    @Mock
    private SlackParticipantConsentService participantConsentService;

    @Mock
    private SlackPersonErasureService personErasureService;

    @Mock
    private SlackMessageService messageService;

    @Mock
    private SlackMentorService mentorService;

    @Mock
    private WeeklyDigestSubscriptions weeklyDigests;

    private SlackInteractivityHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SlackInteractivityHandler(
                workspaceResolver,
                identityResolver,
                appHomeService,
                participantConsentService,
                personErasureService,
                messageService,
                mentorService,
                weeklyDigests,
                directExecutor());
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
    }

    private ObjectNode blockActions(String actionId, String value) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "block_actions");
        payload.put("trigger_id", TRIGGER);
        payload.putObject("team").put("id", TEAM);
        payload.putObject("user").put("id", USER);
        payload.putObject("channel").put("id", CHANNEL);
        payload.putObject("container").put("thread_ts", "100.0");
        ArrayNode actions = payload.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("action_id", actionId);
        action.put("value", value);
        return payload;
    }

    @ParameterizedTest
    @ValueSource(strings = {"research_opt_in", "research_opt_out"})
    void shouldNotWriteAnIndependentResearchDecisionFromOldSlackControls(String action) {
        handler.handleBlockActions(blockActions(action, "false"));
        verifyNoInteractions(participantConsentService, identityResolver, appHomeService, personErasureService);
    }

    @Test
    void appHomeChannelMessageOptOut_recordsIngestion_erasesMemberData_republishesHome() {
        when(identityResolver.resolveMemberId(WORKSPACE_ID, TEAM, USER)).thenReturn(Optional.of(RATER_ID));

        handler.handleBlockActions(blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT, "false"));

        verify(participantConsentService).recordChannelMessageOptOut(WORKSPACE_ID, USER);
        verify(personErasureService).erasePerson(WORKSPACE_ID, RATER_ID, USER);
        verify(messageService)
                .sendEphemeralForWorkspace(
                        eq(WORKSPACE_ID), eq(CHANNEL), eq(USER), anyList(), eq(SlackConsentBlocks.confirmationText()));
        verify(appHomeService).onHomeOpened(TEAM, USER);
    }

    @Test
    void appHomeChannelMessageOptIn_recordsIngestionOptIn_republishesHome_noErase() {
        handler.handleBlockActions(blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN, "true"));

        verify(participantConsentService).recordChannelMessageOptIn(WORKSPACE_ID, USER);
        verify(appHomeService).onHomeOpened(TEAM, USER);
        verifyNoInteractions(personErasureService, messageService);
    }

    @Test
    void inMessageOptOut_recordsChannelMessageOptOut_erasesData_andConfirmsEphemerally() {
        when(identityResolver.resolveMemberId(WORKSPACE_ID, TEAM, USER)).thenReturn(Optional.of(RATER_ID));

        handler.handleBlockActions(blockActions(SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT, ""));

        verify(participantConsentService).recordChannelMessageOptOut(WORKSPACE_ID, USER);
        verify(personErasureService).erasePerson(WORKSPACE_ID, RATER_ID, USER);
        verify(messageService)
                .sendEphemeralForWorkspace(
                        eq(WORKSPACE_ID), eq(CHANNEL), eq(USER), anyList(), eq(SlackConsentBlocks.confirmationText()));
        verifyNoInteractions(appHomeService);
    }

    @Test
    void shouldPassTheVoteToHephWhenTheDeveloperPressesAFeedbackButton() {
        handler.handleBlockActions(blockActions(SlackMentorPosts.FEEDBACK_ACTION, "HELPFUL:" + ANSWER_ID));

        verify(mentorService).handleFeedback(TEAM, CHANNEL, USER, "HELPFUL:" + ANSWER_ID);
    }

    @Test
    void shouldTurnTheWeeklyDigestOffAndSayItOnlyToThemWhenTheDeveloperPressesTurnOff() {
        when(identityResolver.resolveAccountId(TEAM, USER)).thenReturn(Optional.of(ACCOUNT_ID));

        handler.handleBlockActions(blockActions(SlackWeeklyDigestPointer.TURN_OFF_ACTION, ""));

        verify(weeklyDigests).turnOff(ACCOUNT_ID);
        verify(messageService)
                .sendEphemeralForWorkspace(
                        eq(WORKSPACE_ID), eq(CHANNEL), eq(USER), anyList(), eq(SlackWeeklyDigestPointer.TURNED_OFF));
    }

    @Test
    void shouldTurnNothingOffWhenTheSlackUserHasNoAccount() {
        when(identityResolver.resolveAccountId(TEAM, USER)).thenReturn(Optional.empty());

        handler.handleBlockActions(blockActions(SlackWeeklyDigestPointer.TURN_OFF_ACTION, ""));

        verifyNoInteractions(weeklyDigests, messageService);
    }

    @Test
    void unknownAction_isIgnored() {
        handler.handleBlockActions(blockActions("unknown_action", MESSAGE_TS));

        verifyNoInteractions(participantConsentService, appHomeService, personErasureService, messageService);
    }

    /** Runs Slack follow-ups inline so the tests can verify them synchronously. */
    private static ExecutorService directExecutor() {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }

            @Override
            public void shutdown() {}

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return true;
            }

            @Override
            public boolean isTerminated() {
                return true;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
    }
}
