package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.exception.JournalPendingException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentBlocks;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationPosts;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackWeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackConversationService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.EphemeralMessage;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final Instant SIGNED_AT = Instant.parse("2026-10-26T07:33:30Z");

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Mock
    private SlackWorkspaceResolver workspaceResolver;

    @Mock
    private SlackConversationIdentityResolver identityResolver;

    @Mock
    private SlackAppHomeService appHomeService;

    @Mock
    private SlackParticipantConsentService participantConsentService;

    @Mock
    private PrivacyJournal journal;

    @Mock
    private PersonDataCopyFence copyFence;

    @Mock
    private ApplicationEventPublisher events;

    @Mock
    private SlackInteractiveReplyAdapter messageService;

    @Mock
    private SlackConversationService conversationService;

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
                journal,
                copyFence,
                events,
                messageService,
                conversationService,
                weeklyDigests,
                directExecutor());
    }

    private void handleBlockActions(ObjectNode payload) {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(WORKSPACE_ID));
        handler.handleBlockActions(payload, SIGNED_AT, false);
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

    /**
     * The signed team names the workspace. When it names none, because no connection holds it or more than one does,
     * no action writes anything: the resolver answers empty for both (ConnectionServiceTest).
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT,
                SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN,
                SlackConsentBlocks.ACTION_PARTICIPANT_OPT_OUT,
                SlackConversationPosts.FEEDBACK_ACTION,
                SlackWeeklyDigestPointer.TURN_OFF_ACTION
            })
    void shouldWriteNothingWhenTheSignedTeamNamesNoWorkspace(String action) {
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.empty());

        handler.handleBlockActions(blockActions(action, "HELPFUL:" + ANSWER_ID), SIGNED_AT, false);

        verifyNoInteractions(
                identityResolver,
                appHomeService,
                participantConsentService,
                journal,
                copyFence,
                events,
                messageService,
                conversationService,
                weeklyDigests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"research_opt_in", "research_opt_out"})
    void shouldNotWriteAnIndependentResearchDecisionFromOldSlackControls(String action) {
        handleBlockActions(blockActions(action, "false"));
        verifyNoInteractions(participantConsentService, identityResolver, appHomeService, journal);
    }

    @Test
    void shouldQueueAnOptOutConfirmationWithoutCallingTheBucketOnTheTransportPath() {
        UUID fact = UUID.randomUUID();
        when(identityResolver.resolveAccountId(WORKSPACE_ID, TEAM, USER)).thenReturn(Optional.of(RATER_ID));
        var subject = new PersonIdentity(7, USER, TEAM);
        when(identityResolver.nativeSubject(TEAM, USER)).thenReturn(subject);
        doAnswer(invocation -> {
                    Consumer<UUID> confirmation = invocation.getArgument(5);
                    confirmation.accept(fact);
                    return null;
                })
                .when(participantConsentService)
                .requestChannelMessageOptOut(eq(WORKSPACE_ID), eq(TEAM), eq(USER), eq(RATER_ID), any(), any());

        var payload = blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT, "false");
        payload.putObject("view").put("hash", "original-view");
        handleBlockActions(payload);

        verify(events)
                .publishEvent(new SlackInteractivityHandler.OptOutConfirmation(
                        fact, WORKSPACE_ID, RATER_ID, subject, CHANNEL, SIGNED_AT, "original-view"));
        verifyNoInteractions(journal, messageService, appHomeService);
    }

    @Test
    void appHomeChannelMessageOptIn_recordsIngestionOptIn_republishesHome_noErase() {
        handleBlockActions(blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN, "true"));

        verify(participantConsentService)
                .recordChannelMessageOptIn(eq(WORKSPACE_ID), eq(TEAM), eq(USER), any(Instant.class));
        verify(appHomeService).onHomeOpened(TEAM, USER);
        verifyNoInteractions(journal, messageService);
    }

    @Test
    void shouldConfirmAnOptOutOnlyAfterItsCopyShips() {
        UUID fact = UUID.randomUUID();
        var confirmation = new SlackInteractivityHandler.OptOutConfirmation(
                fact, WORKSPACE_ID, RATER_ID, new PersonIdentity(7, USER, TEAM), CHANNEL, SIGNED_AT, "original-view");
        when(participantConsentService.isCurrentWithdrawal(WORKSPACE_ID, USER, SIGNED_AT))
                .thenReturn(true);
        doThrow(JournalPendingException.class).doNothing().when(journal).confirmStored(fact);

        assertThatThrownBy(() -> handler.onOptOutConfirmation(confirmation))
                .isInstanceOf(JournalPendingException.class);
        verifyNoInteractions(messageService, appHomeService);

        handler.onOptOutConfirmation(confirmation);

        var eligible = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(messageService)
                .sendEphemeralForTeam(
                        eq(WORKSPACE_ID),
                        eq(TEAM),
                        eq(CHANNEL),
                        eq(USER),
                        eq(new EphemeralMessage(
                                SlackConsentBlocks.optOutConfirmation(), SlackConsentBlocks.confirmationText())),
                        eligible.capture());
        assertThat(eligible.getValue().getAsBoolean()).isTrue();
        verify(appHomeService).publishHome(WORKSPACE_ID, TEAM, USER, "original-view", eligible.getValue());
    }

    @Test
    void shouldRecheckTheOriginalDecisionAtTheSendBoundary() {
        var confirmation = new SlackInteractivityHandler.OptOutConfirmation(
                UUID.randomUUID(),
                WORKSPACE_ID,
                null,
                new PersonIdentity(7, USER, TEAM),
                CHANNEL,
                SIGNED_AT,
                "original-view");

        handler.onOptOutConfirmation(confirmation);

        var eligible = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(appHomeService)
                .publishHome(eq(WORKSPACE_ID), eq(TEAM), eq(USER), eq("original-view"), eligible.capture());
        assertThat(eligible.getValue().getAsBoolean()).isFalse();
        verify(participantConsentService).isCurrentWithdrawal(WORKSPACE_ID, USER, SIGNED_AT);
        verifyNoInteractions(workspaceResolver);
    }

    @Test
    void shouldSkipADeferredHomeRefreshWithoutTheOriginalViewHash() {
        var confirmation = new SlackInteractivityHandler.OptOutConfirmation(
                UUID.randomUUID(), WORKSPACE_ID, null, new PersonIdentity(7, USER, TEAM), "", SIGNED_AT, null);

        handler.onOptOutConfirmation(confirmation);

        verify(journal).confirmStored(confirmation.fact());
        verifyNoInteractions(appHomeService, messageService);
    }

    @Test
    void shouldDateTheChoiceWithTheActionsSlackTime() {
        ObjectNode payload = blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN, "true");
        ((ObjectNode) payload.path("actions").get(0)).put("action_ts", "1793000010.000100");

        handleBlockActions(payload);

        verify(participantConsentService)
                .recordChannelMessageOptIn(WORKSPACE_ID, TEAM, USER, Instant.parse("2026-10-26T07:33:30.000100Z"));
    }

    @Test
    void shouldSendNoConfirmationWhenALaterChoiceIsStored() {
        when(identityResolver.resolveAccountId(WORKSPACE_ID, TEAM, USER)).thenReturn(Optional.of(RATER_ID));
        handleBlockActions(blockActions(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT, "false"));

        verifyNoInteractions(events, journal, messageService, appHomeService);
    }

    @Test
    void shouldPassTheVoteToHephaestusWhenTheDeveloperPressesAFeedbackButton() {
        handleBlockActions(blockActions(SlackConversationPosts.FEEDBACK_ACTION, "HELPFUL:" + ANSWER_ID));

        verify(conversationService).handleFeedback(TEAM, CHANNEL, USER, "HELPFUL:" + ANSWER_ID);
    }

    @Test
    void shouldTurnTheWeeklyDigestOffAndSayItOnlyToThemWhenTheDeveloperPressesTurnOff() {
        when(identityResolver.resolveLinkedAccountId(TEAM, USER)).thenReturn(Optional.of(ACCOUNT_ID));

        handleBlockActions(blockActions(SlackWeeklyDigestPointer.TURN_OFF_ACTION, ""));

        verify(weeklyDigests).turnOff(ACCOUNT_ID);
        verify(messageService)
                .sendEphemeralForWorkspace(
                        eq(WORKSPACE_ID), eq(CHANNEL), eq(USER), anyList(), eq(SlackWeeklyDigestPointer.TURNED_OFF));
    }

    @Test
    void shouldTurnNothingOffWhenTheSlackUserHasNoAccount() {
        when(identityResolver.resolveLinkedAccountId(TEAM, USER)).thenReturn(Optional.empty());

        handleBlockActions(blockActions(SlackWeeklyDigestPointer.TURN_OFF_ACTION, ""));

        verifyNoInteractions(weeklyDigests, messageService);
    }

    @Test
    void unknownAction_isIgnored() {
        handleBlockActions(blockActions("unknown_action", MESSAGE_TS));

        verifyNoInteractions(participantConsentService, appHomeService, journal, messageService);
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
