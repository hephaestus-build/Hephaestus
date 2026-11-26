package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static de.tum.cit.aet.hephaestus.testconfig.SlackPostTestSupport.admittedPost;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import de.tum.cit.aet.hephaestus.conversation.ConversationStatements;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.action.ActionStopReason;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.credentials.SlackCredentialProvider;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackGatewayTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Call;
import okhttp3.Connection;
import okhttp3.EventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
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
    private WorkspaceSummaryQuery workspaceSummaries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private OwnerScope owners;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SqlStatementCounter statements;

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
        executor = executorFor(slack);
        projector = new SlackConversationProjector(messages, replies, executor, owners, transactionManager);
    }

    private ActionExecutor executorFor(SlackMessageService transport) {
        return executorFor(transport, new OutboundEgressGuard(() -> false));
    }

    private ActionExecutor executorFor(SlackMessageService transport, OutboundEgressGuard guard) {
        var beans = new StaticListableBeanFactory(Map.of("slack", transport));
        var adapter = new SlackPostAdapter(
                beans.getBeanProvider(SlackMessageService.class),
                beans.getBeanProvider(SlackWeeklyDigestPointer.class),
                replies,
                messages,
                completion,
                linkHosts,
                mapper,
                workspaceSummaries,
                guard);
        return ActionTestSupport.withAdapter(context, adapter);
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
        projector.turnChanged(event(turn));
    }

    /** The change that the ledger publishes for the turn as it stands now. */
    private ConversationLedgerEvents.TurnChanged event(ConversationTurn turn) {
        var now = turns.require(workspace.getId(), turn.getId());
        return new ConversationLedgerEvents.TurnChanged(
                workspace.getId(),
                now.getConversationId(),
                now.getAccountId(),
                now.getId(),
                now.getStatus(),
                now.getSurface(),
                now.getAttempt());
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
        when(slack.postAdmitted(anyLong(), any(), any()))
                .thenAnswer(admittedPost(invocation -> new SlackMessageService.Posted("D9", "200.1")));
    }

    /**
     * The projector finds the ended turn's question with the developer's account bound as the owner of a read-only
     * transaction, as the conversation tables' owner policy needs, and wakes the outbox for its post. One statement
     * checks the owner and finds the question, so an absorption cannot commit between two of its reads, and it reads no
     * content.
     */
    @Test
    void shouldReadTheEndedTurnAsItsDeveloperInAReadOnlyTransactionWhenASlackTurnEnds() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Your reviews are quick.");
        var wakes = mock(ActionExecutor.class);
        var reading = new SlackConversationProjector(messages, replies, wakes, owners, transactionManager);
        var ended = event(asked.turn());

        var measured = statements.measureOwners(() -> {
            reading.turnChanged(ended);
            return asked;
        });

        assertThat(ConversationStatements.of(measured))
                .as("the projector's reads of the conversation tables")
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.owner()).as(statement.sql()).isEqualTo(account);
                    assertThat(statement.readOnly()).as(statement.sql()).isTrue();
                    assertThat(statement.sql()).as("no content").doesNotContain("text", "payload");
                });
        verify(wakes).runNow(action(asked));
    }

    /**
     * A change that names no account, as a payload without one reads, or that names another account than the turn's,
     * wakes no post. Neither does a change whose conversation an absorption moved to another account after the change
     * was published. Polling claims the post.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"no account", "another account", "owner moved after the change"})
    void shouldWakeNoPostWhenTheChangeNamesNoAccountOrNotTheTurnsOwner(String change) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Your reviews are quick.");
        var ended = event(asked.turn());
        long named = switch (change) {
            case "no account" -> 0L;
            case "another account" -> ended.accountId() + 1;
            default -> ended.accountId();
        };
        if (change.equals("owner moved after the change")) {
            // An absorption moves the conversation to the claiming account; its turns and messages follow it.
            long claiming = accountId(persistUser("slack-outbox-claiming"));
            jdbc.update("UPDATE conversation SET account_id = ? WHERE id = ?", claiming, main.getId());
        }
        var wakes = mock(ActionExecutor.class);

        new SlackConversationProjector(messages, replies, wakes, owners, transactionManager)
                .turnChanged(new ConversationLedgerEvents.TurnChanged(
                        ended.workspaceId(),
                        ended.conversationId(),
                        named,
                        ended.turnId(),
                        ended.status(),
                        ended.surface(),
                        ended.attempt()));

        verifyNoInteractions(wakes);
        assertThat(replies.forQuestion(workspace.getId(), asked.question().getId()))
                .as("the post stays for the executor's poll")
                .isPresent();
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
                .postAdmitted(
                        eq(workspace.getId()),
                        argThat(post -> "D9".equals(post.channelId())
                                && post.threadTs() == null
                                && post.text().contains("Your reviews")
                                && SlackConversationPosts.metadata(
                                                asked.question().getId())
                                        .equals(post.metadata())),
                        any());
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
        when(slack.postAdmitted(anyLong(), any(), any())).thenAnswer(admittedPost(invocation -> {
            throw new SlackSendException(workspace.getId(), "D9", "transport_failure");
        }));
        changed(running);
        when(slack.readWorkspaceMessages(anyLong(), eq("D9"), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("200.1"), false));
        attempt(asked);
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
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
        verify(slack, never()).postAdmitted(anyLong(), any(), any());
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
    }

    private enum History {
        SKIPPED,
        FOUND,
        EMPTY,
        PARTIAL,
        UNREADABLE
    }

    private static Stream<Arguments> replyRecovery() {
        var withdrawn = new ActionResult.Cancelled(ActionStopReason.WITHDRAWN);
        var reread = new ActionResult.Reread(ConversationSlackReplies.REREAD);
        var delivered = new ActionResult.Done("200.1");
        return Stream.of(
                Arguments.of(false, History.SKIPPED, false, delivered),
                Arguments.of(false, History.SKIPPED, true, withdrawn),
                Arguments.of(true, History.FOUND, false, delivered),
                Arguments.of(true, History.FOUND, true, delivered),
                Arguments.of(true, History.EMPTY, false, new ActionResult.Retry(ConversationSlackReplies.REREAD)),
                Arguments.of(true, History.EMPTY, true, withdrawn),
                Arguments.of(true, History.PARTIAL, false, reread),
                Arguments.of(true, History.PARTIAL, true, reread),
                Arguments.of(true, History.UNREADABLE, false, reread),
                Arguments.of(true, History.UNREADABLE, true, reread));
    }

    @ParameterizedTest(name = "marker={0}, history={1}, removed={2}")
    @MethodSource("replyRecovery")
    void shouldSettleAReplyFromItsMarkerAndSlackHistory(
            boolean marked, History history, boolean removed, ActionResult expected) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete");
        var id = action(asked);
        if (marked) {
            assertThat(ActionTestSupport.claimLane(jdbc, mapper, key(asked))
                            .orElseThrow()
                            .fence()
                            .beginWrite())
                    .isTrue();
            expired(asked);
        }
        if (removed) entries.deleteMessage(workspace.getId(), asked.question().getId());
        switch (history) {
            case SKIPPED -> posts();
            case FOUND ->
                when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                        .thenReturn(new SlackMessageService.Matches(List.of("200.1"), true));
            case EMPTY ->
                when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                        .thenReturn(new SlackMessageService.Matches(List.of(), false));
            case PARTIAL ->
                when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                        .thenReturn(new SlackMessageService.Matches(List.of(), true));
            case UNREADABLE ->
                when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                        .thenThrow(new SlackSendException(workspace.getId(), "D9", "missing_scope"));
        }

        var result = ActionTestSupport.runLane(executor, jdbc, key(asked)).orElseThrow();
        if (expected instanceof ActionResult.Done done) {
            assertThat(result)
                    .isInstanceOfSatisfying(
                            ActionResult.Done.class,
                            actual -> assertThat(actual.providerObjectId()).isEqualTo(done.providerObjectId()));
        } else {
            assertThat(result).isEqualTo(expected);
        }

        verify(slack, times(marked ? 1 : 0)).readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any());
        verify(slack, times(!marked && !removed ? 1 : 0)).postAdmitted(anyLong(), any(), any());
        var stored = jdbc.queryForList(
                "SELECT state, write_started_at IS NOT NULL AS marked FROM agent_action WHERE id=?", id);
        switch (expected) {
            case ActionResult.Done ignored -> {
                if (removed) assertThat(stored).isEmpty();
                else assertThat(stored).containsExactly(Map.of("state", "DONE", "marked", true));
            }
            case ActionResult.Cancelled ignored -> assertThat(stored).isEmpty();
            case ActionResult.Retry ignored ->
                assertThat(stored).containsExactly(Map.of("state", "ADMITTED", "marked", false));
            case ActionResult.Reread ignored ->
                assertThat(stored).containsExactly(Map.of("state", "EXECUTING", "marked", true));
            default -> throw new AssertionError(expected);
        }
    }

    @Test
    void shouldRetryAReplyWhenTheCallIsCancelledDuringAdmissionAndSlackHistoryIsEmpty() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete");
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenReturn(Optional.of(new BearerToken("xoxb-test", null)));
        var currentCall = new AtomicReference<Call>();
        var listener = new EventListener() {
            @Override
            public void callStart(Call call) {
                currentCall.set(call);
            }
        };
        var cancelFirst = new AtomicBoolean(true);
        var guard = new OutboundEgressGuard(() -> {
            // The deadline may cancel the transport while the admission transaction still owns the question lock.
            if (cancelFirst.getAndSet(false)) currentCall.get().cancel();
            return false;
        });

        try (var slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(new MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body("{\"ok\":true,\"messages\":[],\"has_more\":false}")
                    .build());
            slackApi.enqueue(acceptedPost());
            try (var gateway = SlackGatewayTestSupport.withConnectionListener(
                    new SlackProperties(slackApi.url("/").toString()), credentials, listener)) {
                var outbox = executorFor(gateway, guard);
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)))
                        .contains(new ActionResult.Reread(ConversationSlackReplies.REREAD));
                assertThat(slackApi.getRequestCount()).isZero();
                assertThat(jdbc.queryForObject(
                                "SELECT write_started_at IS NOT NULL FROM agent_action WHERE id=?",
                                Boolean.class,
                                action(asked)))
                        .isTrue();

                ActionTestSupport.due(jdbc, key(asked));
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)))
                        .contains(new ActionResult.Retry(ConversationSlackReplies.REREAD));
                var history = Objects.requireNonNull(slackApi.takeRequest(5, TimeUnit.SECONDS));
                assertThat(history.getUrl().encodedPath()).isEqualTo("/api/conversations.history");

                ActionTestSupport.due(jdbc, key(asked));
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)).orElseThrow())
                        .isInstanceOf(ActionResult.Done.class);
                var post = Objects.requireNonNull(slackApi.takeRequest(5, TimeUnit.SECONDS));
                assertThat(post.getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
                assertThat(slackApi.getRequestCount()).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNeverResendAnUnknownPostWhenHistoryIsUnreadableOrHasMorePages(boolean more) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postAdmitted(anyLong(), any(), any())).thenAnswer(admittedPost(invocation -> {
            throw new SlackSendException(workspace.getId(), "D9", "transport_failure");
        }));
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
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
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
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
    }

    @Test
    void shouldWithdrawTheReplyWhenItsQuestionIsDeletedDuringCredentialLookup() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete reply to the deleted question");
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenAnswer(invocation -> {
            // The adapter has built the reply. Commit deletion before the gateway authorizes its write.
            entries.deleteMessage(workspace.getId(), asked.question().getId());
            return Optional.of(new BearerToken("xoxb-test", null));
        });

        try (var slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(acceptedPost());
            try (var gateway = gateway(slackApi, credentials)) {
                assertThat(ActionTestSupport.runLane(executorFor(gateway), jdbc, key(asked)))
                        .contains(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN));
            }
            assertThat(slackApi.getRequestCount()).isZero();
        }
        assertThat(replies.forQuestion(workspace.getId(), asked.question().getId()))
                .isEmpty();
    }

    @Test
    void shouldSuppressTheReplyBeforeMarkingItsWriteWhenSilentModeEngagesDuringAdmission() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete reply");
        var actionId = action(asked);
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenReturn(Optional.of(new BearerToken("xoxb-test", null)));
        var silentModeEngaged = new AtomicBoolean();
        var guard = new OutboundEgressGuard(silentModeEngaged::get);

        try (var slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(acceptedPost());
            try (var gateway = gateway(slackApi, credentials, guard)) {
                var outbox = executorFor(gateway, guard);
                var questionLock = HeldTransaction.holdIn(
                        transactions,
                        jdbc,
                        () -> jdbc.queryForObject(
                                "SELECT id FROM conversation_entry WHERE id = ? FOR UPDATE",
                                UUID.class,
                                asked.question().getId()));
                var attempt = CompletableFuture.supplyAsync(() -> ActionTestSupport.runLane(outbox, jdbc, key(asked)));
                questionLock.commitAfter(attempt, () -> silentModeEngaged.set(true));

                assertThat(attempt.get(20, TimeUnit.SECONDS))
                        .contains(new ActionResult.Cancelled(ActionStopReason.SILENCED));
                assertThat(jdbc.queryForObject(
                                "SELECT write_started_at IS NULL FROM agent_action WHERE id = ?",
                                Boolean.class,
                                actionId))
                        .as("Silent Mode must refuse the write before the outbox records its start")
                        .isTrue();
            }
            assertThat(slackApi.getRequestCount()).isZero();
        }
    }

    @Test
    void shouldWithdrawTheReplyWhenItsQuestionIsDeletedDuringSlackBackoff() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete reply to the deleted question");
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenReturn(Optional.of(new BearerToken("xoxb-test", null)));

        try (var slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(new MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "1")
                    .addHeader("Content-Type", "application/json")
                    .body("{\"ok\":false,\"error\":\"ratelimited\"}")
                    .build());
            slackApi.enqueue(acceptedPost());
            try (var gateway = gateway(slackApi, credentials)) {
                var outbox = executorFor(gateway);
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)))
                        .contains(new ActionResult.Defer(ConversationSlackReplies.REREAD));
                assertThat(slackApi.getRequestCount()).isOne();
                assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");

                entries.deleteMessage(workspace.getId(), asked.question().getId());
                ActionTestSupport.due(jdbc, key(asked));
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)))
                        .contains(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN));
            }
            assertThat(slackApi.getRequestCount()).isOne();
        }
    }

    @Test
    void shouldWithdrawTheReplyWhenItsQuestionIsDeletedDuringTheRetryConnectionSetup() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete reply to the deleted question");
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenReturn(Optional.of(new BearerToken("xoxb-test", null)));
        var requests = new AtomicInteger();
        var deleted = new AtomicBoolean();
        var listener = new EventListener() {
            @Override
            public void callStart(Call call) {
                requests.incrementAndGet();
            }

            @Override
            public void connectionAcquired(Call call, Connection connection) {
                // The retry has its connection, and it has yet to enter the network interceptor.
                if (requests.get() == 2 && deleted.compareAndSet(false, true)) {
                    entries.deleteMessage(workspace.getId(), asked.question().getId());
                }
            }
        };

        try (var slackApi = new MockWebServer()) {
            slackApi.start();
            slackApi.enqueue(new MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "1")
                    .addHeader("Connection", "close")
                    .addHeader("Content-Type", "application/json")
                    .body("{\"ok\":false,\"error\":\"ratelimited\"}")
                    .build());
            slackApi.enqueue(acceptedPost());
            try (var gateway = SlackGatewayTestSupport.withConnectionListener(
                    new SlackProperties(slackApi.url("/").toString()), credentials, listener)) {
                var outbox = executorFor(gateway);
                assertThat(ActionTestSupport.runLane(outbox, jdbc, key(asked)))
                        .contains(new ActionResult.Defer(ConversationSlackReplies.REREAD));
                assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");

                ActionTestSupport.due(jdbc, key(asked));
                var retry = ActionTestSupport.runLane(outbox, jdbc, key(asked));

                assertThat(deleted).isTrue();
                assertThat(jdbc.queryForObject(
                                "SELECT redacted_at IS NOT NULL FROM conversation_entry WHERE id = ?",
                                Boolean.class,
                                asked.question().getId()))
                        .isTrue();
                assertThat(slackApi.getRequestCount())
                        .as("the retry must not post after deletion during connection setup")
                        .isOne();
                assertThat(retry).contains(new ActionResult.Cancelled(ActionStopReason.WITHDRAWN));
            }
        }
    }

    @Test
    void shouldNotResendTheReplyWhenSlackReturns503AfterItsQuestionIsDeleted() throws Exception {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        end(claims.claim(asked.turn()), "Complete reply to the deleted question");
        var credentials = mock(SlackCredentialProvider.class);
        when(credentials.resolve(any())).thenReturn(Optional.of(new BearerToken("xoxb-test", null)));
        var firstRequest = new AtomicBoolean(true);

        try (var slackApi = new MockWebServer()) {
            slackApi.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    if (!firstRequest.getAndSet(false)) return acceptedPost();
                    entries.deleteMessage(workspace.getId(), asked.question().getId());
                    return new MockResponse.Builder()
                            .code(503)
                            .addHeader("Retry-After", "0")
                            .build();
                }
            });
            slackApi.start();
            try (var gateway = gateway(slackApi, credentials)) {
                var result = ActionTestSupport.runLane(executorFor(gateway), jdbc, key(asked));

                assertThat(slackApi.getRequestCount())
                        .as("one outbox admission must issue one Slack request")
                        .isOne();
                assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/chat.postMessage");
                assertThat(jdbc.queryForObject(
                                "SELECT redacted_at IS NOT NULL FROM conversation_entry WHERE id = ?",
                                Boolean.class,
                                asked.question().getId()))
                        .isTrue();
                assertThat(result).contains(new ActionResult.Reread(ConversationSlackReplies.REREAD));
            }
        }
    }

    private static SlackMessageService gateway(MockWebServer slackApi, SlackCredentialProvider credentials) {
        return gateway(slackApi, credentials, new OutboundEgressGuard(() -> false));
    }

    private static SlackMessageService gateway(
            MockWebServer slackApi, SlackCredentialProvider credentials, OutboundEgressGuard guard) {
        var metrics = new SimpleMeterRegistry();
        return new SlackMessageService(
                credentials,
                new SlackRateLimitTracker(metrics),
                guard,
                new SlackMetrics(metrics),
                new SlackProperties(slackApi.url("/").toString()));
    }

    private static MockResponse acceptedPost() {
        return new MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":true,\"channel\":\"D9\",\"ts\":\"200.1\"}")
                .build();
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

    /** A developer's delete or a Slack disconnect removes the question, and the lane withdraws its pending reply. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldWithdrawThePendingReplyAndSendNothingWhenItsQuestionIsRemoved(boolean disconnect) {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Private complete answer");
        var id = action(asked);

        if (disconnect) context.getBean(SlackWorkspaceContentEraser.class).eraseWorkspace(workspace.getId());
        else entries.deleteMessage(workspace.getId(), asked.question().getId());
        attempt(asked);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_action WHERE id=?", Long.class, id))
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
        when(slack.postAdmitted(anyLong(), any(), any())).thenAnswer(admittedPost(invocation -> {
            throw new SlackSendException(workspace.getId(), "D9", "transport_failure");
        }));
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
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
    }

    @Test
    void shouldKeepAnUnknownEffectUnconfirmedWhenSlackIsDisconnected() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postAdmitted(anyLong(), any(), any())).thenAnswer(admittedPost(invocation -> {
            throw new SlackSendException(workspace.getId(), "D9", "transport_failure");
        }));
        changed(running);
        when(slack.isConnected(workspace.getId())).thenReturn(false);
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.UNCERTAIN);
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
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
                .postAdmitted(
                        anyLong(),
                        argThat(post ->
                                post.text().contains(SlackConversationPosts.aiLabel(workspace.getDisplayName()))),
                        any());
    }

    @Test
    void shouldHonorRateLimitBackoffAcrossRepeatedNotificationsAndPostOnlyOnceAfterIt() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        end(running, "Complete");
        when(slack.postAdmitted(anyLong(), any(), any()))
                .thenAnswer(admittedPost(invocation -> {
                    throw new SlackSendException(workspace.getId(), "D9", "ratelimited", 5000L);
                }))
                .thenAnswer(admittedPost(invocation -> new SlackMessageService.Posted("D9", "200.1")));
        changed(running);
        changed(running);
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
        attempt(asked);
        assertThat(replies.delivery(workspace.getId(), key(asked)))
                .isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        verify(slack, times(2)).postAdmitted(anyLong(), any(), any());
    }

    @Test
    void shouldFailClaimedFeedbackWithoutLinkingAnAnswerWhenSilentModePreventsThePost() {
        var asked = ask(main, ConversationTurn.Surface.SLACK);
        var running = claims.claim(asked.turn());
        UUID feedback = endWithFeedback(running);
        when(slack.postAdmitted(anyLong(), any(), any())).thenAnswer(admittedPost(invocation -> {
            throw new SlackSendException(workspace.getId(), "D9", "silent_mode_engaged");
        }));
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
        verify(slack, times(1)).postAdmitted(anyLong(), any(), any());
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
