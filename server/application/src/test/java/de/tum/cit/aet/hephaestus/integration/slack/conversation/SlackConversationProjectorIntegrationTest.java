package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Only complete admitted answers reach Slack, once, through the real outbox. */
class SlackConversationProjectorIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private ConversationTurnCompletion completion;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationSlackMessages messages;

    @Autowired
    private ConversationSlackReplies replies;

    @Autowired
    private ModelLinkHosts linkHosts;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private Workspace workspace;
    private Conversation main;
    private ActionExecutor executor;
    private SlackConversationProjector projector;
    private long account;
    private int nextQuestion = 100;

    @BeforeEach
    void setup() {
        var developer = persistUser("slack-outbox");
        workspace = createWorkspace("slack-outbox", "Slack", "slack-outbox", AccountType.ORG, developer);
        account = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), account);
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), "T1", "U1", "D9");
        when(slack.isConnected(workspace.getId())).thenReturn(true);
        var beans = new StaticListableBeanFactory(Map.of("slack", slack));
        var adapter = new SlackPostAdapter(
                beans.getBeanProvider(SlackMessageService.class),
                beans.getBeanProvider(SlackWeeklyDigestPointer.class),
                replies,
                messages,
                completion,
                linkHosts,
                mapper);
        executor = ActionTestSupport.withAdapter(context, adapter);
        projector = new SlackConversationProjector(messages, replies, executor);
    }

    private ConversationTurnService.Submission ask(Conversation conversation, ConversationTurn.Surface surface) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        surface, "How are my reviews?", "slack:" + UUID.randomUUID(), nextQuestion++ + ".1", null));
    }

    private void end(ConversationTurn running, String text) {
        completion.end(
                workspace.getId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), text)),
                        null,
                        null,
                        null,
                        null));
    }

    private void changed(ConversationTurn turn) {
        var now = turns.require(workspace.getId(), turn.getId());
        projector.turnChanged(new ConversationLedgerEvents.TurnChanged(
                workspace.getId(),
                now.getConversationId(),
                now.getId(),
                now.getStatus(),
                now.getSurface(),
                now.getAttempt()));
    }

    private String key(ConversationTurnService.Submission asked) {
        return ConversationSlackReplies.replyKey(asked.question().getId());
    }

    private UUID action(ConversationTurnService.Submission asked) {
        return replies.forQuestion(workspace.getId(), asked.question().getId()).orElseThrow();
    }

    private void attempt(ConversationTurnService.Submission asked) {
        ActionTestSupport.due(jdbc, key(asked));
        ActionTestSupport.runLane(executor, jdbc, key(asked));
    }

    private void expired(ConversationTurnService.Submission asked) {
        ActionTestSupport.expireLease(jdbc, key(asked));
    }

    private void posts() {
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted("D9", "200.1"));
    }

    @Test
    void shouldSendNoPartialAnswerAndPostTheCompleteAnswerOnlyOnce() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        changed(running);
        verifyNoInteractions(slack);
        assertThat(replies.forQuestion(workspace.getId(), asked.question().getId()))
                .isEmpty();
        end(running, "Your reviews explain their decisions.");
        posts();
        changed(running);
        changed(running);
        attempt(asked);
        verify(slack, times(1))
                .postMessage(
                        eq(workspace.getId()),
                        eq("D9"),
                        isNull(),
                        anyList(),
                        contains("Your reviews"),
                        eq(SlackConversationPosts.metadata(asked.question().getId())));
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        assertThat(jdbc.queryForObject(
                        "SELECT payload IS NULL FROM agent_action WHERE id=?", Boolean.class, action(asked)))
                .isTrue();
    }

    @Test
    void shouldReadBackAMessageAfterTheProviderReceiptWasLostWithoutPostingAgain() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete answer");
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "transport_failure"));
        changed(running);
        when(slack.readWorkspaceMessages(anyLong(), eq("D9"), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("200.1"), false));
        attempt(asked);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        assertThat(jdbc.queryForObject(
                        "SELECT slack_ts FROM conversation_entry WHERE turn_id=? AND kind='ANSWER' ORDER BY seq DESC LIMIT 1",
                        String.class,
                        running.getId()))
                .isEqualTo("200.1");
    }

    @Test
    void shouldReadBackAfterAnExecutorCrashAtTheWriteFence() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        ActionTestSupport.due(jdbc, key(asked));
        assertThat(ActionTestSupport.claimLane(jdbc, mapper, key(asked))
                        .orElseThrow()
                        .fence()
                        .beginWrite())
                .isTrue();
        expired(asked);
        when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("200.1"), false));
        attempt(asked);
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNeverResendAnUnknownPostWhenHistoryIsUnreadableOrHasMorePages(boolean more) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "transport_failure"));
        changed(running);
        if (more)
            when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                    .thenReturn(new SlackMessageService.Matches(List.of(), true));
        else
            when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                    .thenThrow(new SlackSendException(workspace.getId(), "D9", "missing_scope"));
        attempt(asked);
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.UNCERTAIN);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldKeepTerminalDeliveryInTheOutboxAfterGenericRetention() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        posts();
        changed(running);
        jdbc.update("""
            WITH old AS (DELETE FROM agent_action WHERE id=? RETURNING *)
            INSERT INTO agent_action(id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,lease_epoch,readback_ref,stop_reason,created_at,updated_at,ended_at)
            SELECT id,workspace_id,kind,lane_key,idempotency_key,state,payload,subject_account_id,
                attempts,max_attempts,next_attempt_at,lease_epoch,readback_ref,stop_reason,created_at,updated_at,
                ended_at-interval '31 days' FROM old
            """, action(asked));
        ActionTestSupport.pruneEnded(context, Duration.ofDays(30));
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        changed(running);
        entries.deleteMessage(workspace.getId(), asked.question().getId());
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM agent_action WHERE idempotency_key=?", Long.class, key(asked)))
                        .isZero());
        changed(running);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldRemoveAnUnknownOutcomeWhenRedactionCommitsDuringItsSettlement() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        var claim = ActionTestSupport.claimLane(jdbc, mapper, key(asked)).orElseThrow();
        assertThat(claim.fence().beginWrite()).isTrue();
        var unknown = new ActionResult.Uncertain(ActionStopReason.WRITE_UNCONFIRMED);
        var ending =
                HeldTransaction.holdIn(transactions, jdbc, () -> ActionTestSupport.record(context, claim, unknown));
        // Tests run durable listeners inline, so this future includes the cleanup after redaction commits.
        var redaction = CompletableFuture.runAsync(
                () -> entries.deleteMessage(workspace.getId(), asked.question().getId()));
        ending.commitAfter(redaction);
        redaction.get(20, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE id=?", Long.class, claim.id()))
                .isZero();
        verifyNoInteractions(slack);
    }

    @Test
    void shouldEraseThePendingPayloadAndSendNothingAfterErasure() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Private complete answer");
        var id = action(asked);
        context.getBean(ActionPersonDataCatalog.class).eraseAccount(account);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE id=?", Long.class, id))
                .isZero();
        changed(running);
        verifyNoInteractions(slack);
    }

    private UUID endWithFeedback(ConversationTurn running) {
        String feedbackText = "Your review explains the tradeoff.";
        UUID feedback =
                ConversationFeedbackRows.claimedBy(jdbc, workspace.getId(), account, running.getId(), feedbackText);
        String call = "call-" + UUID.randomUUID();
        completion.end(
                workspace.getId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.showFeedback(
                                        ConversationLedgerFixtures.piId(),
                                        "Here is what I saw.",
                                        Map.of(call, feedback),
                                        feedbackText),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), call, "show_feedback", "Shown", false),
                                ConversationLedgerFixtures.answer(
                                        ConversationLedgerFixtures.piId(), "What do you think?")),
                        null,
                        null,
                        null,
                        null));
        return feedback;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldFailUnsentFeedbackEvenWhenItsQuestionOrSlackDestinationIsGone(boolean deleteQuestion) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        UUID feedback = endWithFeedback(running);
        if (deleteQuestion)
            entries.deleteMessage(workspace.getId(), asked.question().getId());
        else conversations.clearSlackIdentities(workspace.getId());
        attempt(asked);
        ConversationReceipts.awaitSettled(jdbc);
        assertThat(jdbc.queryForObject("SELECT state FROM feedback WHERE id=?", String.class, feedback))
                .isEqualTo("FAILED");
        verifyNoInteractions(slack);
    }

    @Test
    void shouldConfirmFeedbackOnlyAfterSlackReadbackConfirmsTheCompleteQuote() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        UUID feedback = endWithFeedback(running);
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "transport_failure"));
        changed(running);
        ConversationReceipts.awaitSettled(jdbc);
        assertThat(jdbc.queryForObject("SELECT state FROM feedback WHERE id=?", String.class, feedback))
                .isEqualTo("UNCONFIRMED");
        when(slack.readWorkspaceMessages(anyLong(), eq("D9"), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("200.1"), false));
        attempt(asked);
        ConversationReceipts.awaitSettled(jdbc);
        assertThat(jdbc.queryForObject("SELECT state FROM feedback WHERE id=?", String.class, feedback))
                .isEqualTo("DELIVERED");
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldKeepAnUnknownEffectUnconfirmedWhenSlackIsDisconnected() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "transport_failure"));
        changed(running);
        when(slack.isConnected(workspace.getId())).thenReturn(false);
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.UNCERTAIN);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldEraseTheRetainedOutcomeAndProviderReferenceWithTheAccount() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        posts();
        changed(running);
        UUID id = action(asked);
        context.getBean(ActionPersonDataCatalog.class).eraseAccount(account);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE id=?", Long.class, id))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepTheAiDisclosureForTheFirstRealAnswerAfterAFailureOrBlankAnswer(boolean failed) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        completion.end(
                workspace.getId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnCompletion.Ending(
                        failed ? ConversationTurn.Status.FAILED : ConversationTurn.Status.DONE,
                        List.of(ConversationLedgerFixtures.answer(
                                ConversationLedgerFixtures.piId(), failed ? "unfinished" : "")),
                        failed ? "unavailable" : null,
                        null,
                        null,
                        null));
        posts();
        changed(running);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_entry WHERE turn_id=? AND kind='ANSWER' AND slack_ts IS NOT NULL",
                        Long.class,
                        running.getId()))
                .isZero();
        var next = ask(main, ConversationTurn.Surface.SLACK);
        var nextRunning = claims.claim(next.turn());
        end(nextRunning, "A complete answer");
        changed(nextRunning);
        verify(slack)
                .postMessage(
                        anyLong(), anyString(), any(), anyList(), contains(SlackConversationPosts.AI_LABEL), any());
    }

    @Test
    void shouldHonorRateLimitBackoffAcrossRepeatedNotificationsAndPostOnlyOnceAfterIt() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "ratelimited", 5000L))
                .thenReturn(new SlackMessageService.Posted("D9", "200.1"));
        changed(running);
        changed(running);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        verify(slack, times(2)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldFailClaimedFeedbackWithoutLinkingAnAnswerWhenSilentModePreventsThePost() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        UUID feedback = endWithFeedback(running);
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), "D9", "silent_mode_engaged"));
        changed(running);
        ConversationReceipts.awaitSettled(jdbc);
        assertThat(jdbc.queryForObject("SELECT state FROM feedback WHERE id=?", String.class, feedback))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT stop_reason FROM agent_action WHERE id=?", String.class, action(asked)))
                .isEqualTo("SILENCED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM conversation_entry WHERE turn_id=? AND kind='ANSWER' AND slack_ts IS NOT NULL",
                        Long.class,
                        running.getId()))
                .isZero();
        attempt(asked);
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldSendNothingToSlackWhenTheTurnWasStoppedInTheApp() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        completion.end(
                workspace.getId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnCompletion.Ending(
                        ConversationTurn.Status.STOPPED, List.of(), null, null, null, null));
        changed(running);
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked))).isEqualTo(ConversationEntry.SlackDelivery.FAILED);
        verifyNoInteractions(slack);
    }

    @Test
    void shouldSendNothingForQuestionsAskedInTheApp() {
        var asked = ask(main, ConversationTurn.Surface.APP);
        var running = claims.claim(asked.turn());
        end(running, "Only in app");
        changed(running);
        verifyNoInteractions(slack);
    }
}
