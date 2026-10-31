package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slack.api.model.Message;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.MarkdownBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.ConversationReceiptsDue;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService.HistoryPage;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnWaits;
import de.tum.cit.aet.hephaestus.practices.feedback.ConversationFeedbackRows;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.ConversationReceipts;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hephaestus's Slack replies: what reaches Slack, once, and what the ledger records about it and the feedback it showed. The
 * test hands the projector each ledger notification itself. The projector works on the test's thread, its clock is the
 * test's, and its wake-ups wait until the test runs them.
 */
class SlackMentorProjectorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String TEAM = "T1";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";
    private static final String BOT_USER = "UBOT";
    private static final String BOT_ID = "BHEPH";
    private static final String WORKER = "worker-slack";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String FEEDBACK = "Your pull request names the decision but not why it beat the other option.";

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private MentorSlackDelivery delivery;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorLedgerNotifications notifications;

    @MockitoSpyBean
    private MentorTurnCompletion completion;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private PersonDataWriteFence writeFence;

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.now(), ZoneOffset.UTC);
    private final ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> pendingWakeUp = mock(ScheduledFuture.class);
    private final List<Runnable> wakeUps = new ArrayList<>();

    private User developer;
    private Workspace workspace;
    private MentorConversation main;
    private SlackMentorProjector projector;
    private long developerAccount;
    private @Nullable UUID shownFeedback;

    @BeforeEach
    void setUpProjector() {
        // The clock starts with the test instance, which can precede the context's start; turns record real time.
        Duration behind = Duration.between(clock.instant(), Instant.now());
        if (behind.isPositive()) {
            clock.advance(behind);
        }
        developer = persistUser("slack-projector-developer");
        workspace =
                createWorkspace("slack-projector", "Slack Projector", "slack-projector", AccountType.ORG, developer);
        developerAccount = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), developerAccount);
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, SLACK_USER, CHANNEL);
        when(slack.isConnected(workspace.getId())).thenReturn(true);
        when(slack.resolveBot(workspace.getId()))
                .thenReturn(Optional.of(new SlackMessageService.BotIdentity(BOT_USER, BOT_ID)));
        when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(call -> {
                    wakeUps.add(call.getArgument(0));
                    return pendingWakeUp;
                });
        // No Hephaestus capacity: turns of earlier tests can hold Hephaestus slots, and no question here says that all
        // are taken.
        projector = projector(new MentorTurnWaits(turnRepository, events, null));
    }

    private SlackMentorProjector projector(MentorTurnWaits waits) {
        return new SlackMentorProjector(
                notifications,
                delivery,
                turns,
                waits,
                completion,
                slack,
                new SlackMentorPoster(slack, delivery),
                clock,
                meters,
                new OnTheCallersThread(),
                timer);
    }

    private MentorTurnService.Submission ask(MentorConversation conversation, String text, String ts) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(MentorTurn.Surface.SLACK, text, "slack:T1:D9:" + ts, ts, null));
    }

    /** Runs each projection when it is asked for, so its effect is there when {@code turnChanged} returns. */
    private static final class OnTheCallersThread extends AbstractExecutorService {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }

    /** The ledger notification of the turn's current state, as the projector hears it. */
    private void changed(MentorTurn turn) {
        MentorTurn now = turns.require(workspace.getId(), turn.getId());
        projector.turnChanged(new MentorLedgerEvents.TurnChanged(
                workspace.getId(),
                now.getConversationId(),
                now.getId(),
                now.getStatus(),
                now.getSurface(),
                now.getAttempt()));
    }

    /** Hephaestus writes more of the answer once the stream may take more text. */
    private void writes(MentorTurn turn, String text) {
        clock.advance(SlackMentorProjector.APPEND_INTERVAL);
        turns.updatePartial(turn.getId(), turn.getAttempt(), WORKER, partial(text));
        changed(turn);
    }

    private void end(MentorTurn turn, MentorTurn.Status outcome, String answer, String error) {
        List<JsonNode> entries = answer.isEmpty() ? List.of() : List.of(answer(answer));
        end(turn, outcome, entries, error);
    }

    private void end(MentorTurn turn, MentorTurn.Status outcome, List<JsonNode> entries, String error) {
        completion.end(
                turn.getId(),
                turn.getAttempt(),
                WORKER,
                new MentorTurnCompletion.Ending(outcome, entries, error.isEmpty() ? null : error, null, null));
    }

    private static ObjectNode answer(String text) {
        return MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), text);
    }

    /**
     * Hephaestus says something, shows feedback in the same message, then writes {@code closing}. The running attempt holds
     * the claim on the feedback's conversation copy, as its {@code show_feedback} call left it.
     */
    private List<JsonNode> linkingAnswer(MentorTurn running, String closing) {
        shownFeedback = ConversationFeedbackRows.claimedBy(
                jdbc, workspace.getId(), developerAccount, running.getId(), FEEDBACK);
        String callId = "call-" + UUID.randomUUID();
        return List.of(
                MentorLedgerFixtures.showFeedback(
                        MentorLedgerFixtures.piId(), "Here is what I saw.", Map.of(callId, shownFeedback), FEEDBACK),
                MentorLedgerFixtures.toolResult(
                        MentorLedgerFixtures.piId(),
                        callId,
                        "show_feedback",
                        "Shown to the developer: feedback on prepared feedback " + shownFeedback,
                        false),
                answer(closing));
    }

    /** Whether a receipt settled the shown feedback's conversation copy. */
    private boolean copySettled() {
        ConversationReceipts.awaitSettled(jdbc);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT posted_at IS NOT NULL OR last_error IS NOT NULL FROM feedback_copy WHERE feedback_id=?",
                Boolean.class,
                Objects.requireNonNull(shownFeedback)));
    }

    private FeedbackState feedbackState() {
        ConversationReceipts.awaitSettled(jdbc);
        return FeedbackState.valueOf(Objects.requireNonNull(jdbc.queryForObject(
                "SELECT state FROM feedback WHERE id=?", String.class, Objects.requireNonNull(shownFeedback))));
    }

    /** A snapshot of a running turn that runs a tool, with the stage the host names for it. */
    private static ObjectNode staged(String stage) {
        ObjectNode snapshot =
                NODES.objectNode().put("activity", "tool").put("tool", "read").put("stage", stage);
        snapshot.putArray("messages");
        return snapshot;
    }

    private static ObjectNode partial(String text) {
        ObjectNode snapshot = NODES.objectNode().put("activity", "writing");
        snapshot.putArray("messages")
                .addObject()
                .put("role", "assistant")
                .putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", text);
        return snapshot;
    }

    private MentorEntry entry(UUID id) {
        return entryRepository.findById(id).orElseThrow();
    }

    private MentorEntry question(MentorTurnService.Submission asked) {
        return entry(asked.question().getId());
    }

    private double replies(String state) {
        return meters.counter("mentor.slack.reply", "state", state).count();
    }

    private void stopStreamFails(String error) {
        doThrow(new SlackSendException(workspace.getId(), CHANNEL, error))
                .when(slack)
                .stopStream(anyLong(), anyString(), anyString(), any());
    }

    /** A Slack thread on the top-level question at {@code 100.1}, and its own question running at {@code 100.5}. */
    private MentorTurnService.Submission askInThread() {
        MentorTurnService.Submission parent = ask(main, "How are my reviews?", "100.1");
        MentorConversation thread =
                conversations.openThread(workspace.getId(), parent.question().getId());
        MentorTurnService.Submission asked = ask(thread, "Tell me more", "100.5");
        assertThat(turns.claimNext(WORKER, LEASE)).isPresent(); // the main conversation's turn
        assertThat(turns.claimNext(WORKER, LEASE).map(MentorTurn::getId))
                .contains(asked.turn().getId());
        return asked;
    }

    private MentorTurn running(MentorTurnService.Submission asked) {
        return turns.require(workspace.getId(), asked.turn().getId());
    }

    private MentorEntry answerOf(MentorTurn turn) {
        UUID answerId = turns.answer(workspace.getId(), turn.getId()).answerEntryId();
        return entry(Objects.requireNonNull(answerId));
    }

    private void postsAt(String ts) {
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, ts));
    }

    /** The Markdown of every message posted to the top level of the DM. */
    @SuppressWarnings("unchecked") // ArgumentCaptor cannot name a generic type.
    private List<String> postedTopLevel() {
        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slack, atLeast(0)).postMessage(anyLong(), eq(CHANNEL), isNull(), blocks.capture(), anyString(), any());
        List<String> texts = new ArrayList<>();
        for (List<LayoutBlock> post : blocks.getAllValues()) {
            for (LayoutBlock block : post) {
                if (block instanceof MarkdownBlock markdown) {
                    texts.add(markdown.getText());
                }
            }
        }
        return texts;
    }

    /** The blocks that replaced the message of the question's stream at {@code 300.1}. */
    @SuppressWarnings("unchecked") // ArgumentCaptor cannot name a generic type.
    private List<LayoutBlock> replacement(MentorTurnService.Submission asked) {
        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slack)
                .updateMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        eq("300.1"),
                        blocks.capture(),
                        anyString(),
                        eq(SlackMentorPosts.metadata(asked.question().getId())));
        return blocks.getValue();
    }

    /** Slack's history of the thread: one page, with the messages its filter takes and nothing after it. */
    private void historyHolds(Message... messages) {
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(CHANNEL), eq("100.1"), anyString(), any()))
                .thenAnswer(call -> {
                    Predicate<Message> match = call.getArgument(4);
                    return new SlackMessageService.Matches(
                            Stream.of(messages)
                                    .filter(match)
                                    .map(Message::getTs)
                                    .toList(),
                            false);
                });
    }

    /** A message of the app's bot. */
    private static Message botMessage(String ts, Message.@Nullable Metadata metadata) {
        Message message = new Message();
        message.setTs(ts);
        // Slack's bot message: `bot_id` always, `user` not always.
        message.setBotId(BOT_ID);
        message.setMetadata(metadata);
        return message;
    }

    private static String markdownOf(List<LayoutBlock> blocks) {
        return ((MarkdownBlock) blocks.getFirst()).getText();
    }

    private void appendFails(SlackSendException refusal) {
        doThrow(refusal).when(slack).appendStream(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * Each Slack call that sets the session status of a thread, in the order Slack takes them: {@code status:<status>}
     * for {@code agents.sessions.setStatus}, {@code stop:<ts>:<status>} for the status that a stream's end sets.
     */
    private final List<String> sessionCalls = new CopyOnWriteArrayList<>();

    private static String status(boolean working) {
        return working ? "processing" : "active";
    }

    private void recordSessionCalls() {
        doAnswer(call -> {
                    sessionCalls.add("status:" + status(call.<Boolean>getArgument(3)));
                    return null;
                })
                .when(slack)
                .setSessionStatus(anyLong(), anyString(), anyString(), anyBoolean());
        doAnswer(call -> {
                    recordStop(call.getArgument(2), call.getArgument(3));
                    return null;
                })
                .when(slack)
                .stopStream(anyLong(), anyString(), anyString(), any());
    }

    private void recordStop(String ts, SlackMessageService.StreamStop stop) {
        sessionCalls.add("stop:" + ts + ":" + status(stop.working()));
    }

    /** Whether another transaction waits for a lock of the transaction on {@code backendPid}. */
    private boolean blocksAnother(int backendPid) {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid)))",
                Boolean.class,
                backendPid));
    }

    @Test
    void shouldTellTheDeveloperOnceThatTheQuestionWaitsAndRemoveTheNoticeWhenTheTurnRuns() {
        when(slack.postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        isNull(),
                        eq(List.of()),
                        eq(SlackMentorProjector.WAITING_NOTICE),
                        any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "500.1"));
        MentorTurn queued = ask(main, "Why is this not met?", "100.1").turn();
        changed(queued);
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(wakeUps).hasSize(1);

        clock.advance(SlackMentorProjector.WAITING_NOTICE_AFTER.plusMinutes(1));
        wakeUps.forEach(Runnable::run);
        changed(queued);
        verify(slack, times(1))
                .postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        isNull(),
                        eq(List.of()),
                        eq(SlackMentorProjector.WAITING_NOTICE),
                        any());

        changed(turns.claimNext(WORKER, LEASE).orElseThrow());

        verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.1");
    }

    @Test
    void shouldSayThatHephAnswersOtherQuestionsWhenTheQuestionWaitsForAFreeHeph() {
        User colleague = persistUser("slack-projector-colleague");
        ensureMembership(workspace, colleague, Membership.WorkspaceRole.MEMBER);
        MentorConversation colleagues = conversations.findOrCreateMain(workspace.getId(), accountId(colleague));
        MentorTurn colleaguesTurn = turns.submit(
                        workspace.getId(),
                        colleagues.getId(),
                        new MentorTurnService.Question(
                                MentorTurn.Surface.APP,
                                "What changed?",
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .turn();
        assertThat(turns.claimNext(WORKER, LEASE).map(MentorTurn::getId)).contains(colleaguesTurn.getId());
        MentorTurn queued = ask(main, "Why is this not met?", "100.1").turn();
        // Every Hephaestus slot is taken: the deployment has as many as turns hold now.
        projector = projector(new MentorTurnWaits(turnRepository, events, Math.toIntExact(turnRepository.countHeld())));

        clock.advance(SlackMentorProjector.WAITING_NOTICE_AFTER.plusMinutes(1));
        changed(queued);

        verify(slack)
                .postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        isNull(),
                        eq(List.of()),
                        eq(SlackMentorProjector.WAITING_FOR_HEPH_NOTICE),
                        any());
        verify(slack, never())
                .postMessage(anyLong(), anyString(), any(), anyList(), eq(SlackMentorProjector.WAITING_NOTICE), any());
    }

    @Test
    void shouldRemoveTheNoticeWhenTheDeveloperStopsAQuestionThatStillWaits() {
        when(slack.postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        isNull(),
                        eq(List.of()),
                        eq(SlackMentorProjector.WAITING_NOTICE),
                        any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "500.1"));
        MentorTurn queued = ask(main, "Why is this not met?", "100.1").turn();
        changed(queued);
        clock.advance(SlackMentorProjector.WAITING_NOTICE_AFTER.plusMinutes(1));
        wakeUps.forEach(Runnable::run);

        changed(turns.stop(workspace.getId(), queued.getId()));

        verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.1");
    }

    @Test
    void shouldNotSayThatATurnHasNotStartedWhenItRanAndWaitsAgain() {
        MentorTurn asked = ask(main, "Why is this not met?", "100.1").turn();
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        turns.renewLease(running.getId(), running.getAttempt(), WORKER, Duration.ofSeconds(-1));
        assertThat(turns.expireLease(running.getId())).isPresent();
        assertThat(turns.require(workspace.getId(), asked.getId()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);

        clock.advance(SlackMentorProjector.WAITING_NOTICE_AFTER.plusMinutes(1));
        changed(asked);
        wakeUps.forEach(Runnable::run);

        verify(slack, never())
                .postMessage(anyLong(), anyString(), any(), anyList(), eq(SlackMentorProjector.WAITING_NOTICE), any());
    }

    /** A wake-up the projector scheduled: it runs once, when the test's clock reaches it, unless it was cancelled. */
    private static final class Due {
        final Runnable task;
        final Instant at;
        boolean ran;
        boolean cancelled;

        Due(Runnable task, Instant at) {
            this.task = task;
            this.at = at;
        }
    }

    private final List<Due> due = new ArrayList<>();

    /** A projector whose timer keeps each wake-up's time, so a test sees when the projector looks again. */
    private SlackMentorProjector timedProjector() {
        ScheduledExecutorService dueTimer = mock(ScheduledExecutorService.class);
        doAnswer(call -> {
                    Due wakeUp = new Due(call.getArgument(0), clock.instant().plusMillis(call.<Long>getArgument(1)));
                    due.add(wakeUp);
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    when(future.isDone()).thenAnswer(done -> wakeUp.ran || wakeUp.cancelled);
                    when(future.cancel(anyBoolean())).thenAnswer(cancel -> {
                        wakeUp.cancelled = true;
                        return true;
                    });
                    return future;
                })
                .when(dueTimer)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        return new SlackMentorProjector(
                notifications,
                delivery,
                turns,
                new MentorTurnWaits(turnRepository, events, null),
                completion,
                slack,
                new SlackMentorPoster(slack, delivery),
                clock,
                meters,
                new OnTheCallersThread(),
                dueTimer);
    }

    /** Moves the clock by {@code by} and runs each wake-up that is then due, once. */
    private void advance(Duration by) {
        clock.advance(by);
        for (Due wakeUp : List.copyOf(due)) {
            if (!wakeUp.ran && !wakeUp.cancelled && !wakeUp.at.isAfter(clock.instant())) {
                wakeUp.ran = true;
                wakeUp.task.run();
            }
        }
    }

    private void progressPostsAt(String stage, String ts) {
        when(slack.postMessage(eq(workspace.getId()), eq(CHANNEL), isNull(), eq(List.of()), eq(stage), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, ts));
    }

    @Test
    void shouldSayWhatHephaestusDoesOnARunningTopLevelQuestionAndRemoveItWhenTheReplyPosts() {
        projector = timedProjector();
        progressPostsAt("Reading your pull requests…", "500.1");
        ask(main, "Is anything of mine waiting for a review?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(running);

        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER.minusSeconds(1));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        advance(Duration.ofSeconds(1));
        verify(slack, times(1))
                .postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), eq("Reading your pull requests…"), any());

        advance(SlackMentorProjector.PROGRESS_INTERVAL);
        verify(slack, never()).updateMessage(anyLong(), anyString(), anyString(), anyList(), anyString(), any());

        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Loading the repository…"));
        advance(SlackMentorProjector.PROGRESS_INTERVAL);
        verify(slack)
                .updateMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        eq("500.1"),
                        eq(List.of()),
                        eq("Loading the repository…"),
                        any());

        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted(CHANNEL, "200.1"));
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you make of the gap?"), "");
        changed(running);
        changed(running);

        InOrder order = inOrder(slack);
        order.verify(slack)
                .postMessage(anyLong(), eq(CHANNEL), isNull(), anyList(), startsWith("Here is what I saw."), any());
        order.verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.1");
        advance(SlackMentorProjector.PROGRESS_INTERVAL.multipliedBy(3));
        verify(slack, times(1)).updateMessage(anyLong(), anyString(), anyString(), anyList(), anyString(), any());
    }

    @Test
    void shouldShowProgressOnTimeWhenTheQuestionWaitedBeforeItsTurnRan() {
        projector = timedProjector();
        progressPostsAt("Thinking…", "500.1");
        MentorTurn queued = ask(main, "How are my reviews?", "100.1").turn();
        changed(queued);
        advance(Duration.ofSeconds(1));
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Thinking…"));
        changed(running);

        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);

        verify(slack).postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), eq("Thinking…"), any());
    }

    @Test
    void shouldRemoveTheProgressOfALostAttemptAndShowItAgainWhenTheRetryRuns() {
        projector = timedProjector();
        progressPostsAt("Reading your pull requests…", "500.1");
        MentorTurn asked =
                ask(main, "Is anything of mine waiting for a review?", "100.1").turn();
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(running);
        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);

        turns.renewLease(running.getId(), running.getAttempt(), WORKER, Duration.ofSeconds(-1));
        assertThat(turns.expireLease(running.getId())).isPresent();
        changed(turns.require(workspace.getId(), asked.getId()));
        verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.1");

        progressPostsAt("Reading your pull requests…", "500.2");
        MentorTurn retried = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(retried.getId(), retried.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(retried);
        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);

        verify(slack, times(2))
                .postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), eq("Reading your pull requests…"), any());
    }

    @Test
    void shouldPostProgressOnceAndFindItInTheHistoryWhenSlackDidNotAnswerThePost() {
        projector = timedProjector();
        when(slack.postMessage(
                        anyLong(), eq(CHANNEL), isNull(), eq(List.of()), eq("Reading your pull requests…"), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));
        ask(main, "Is anything of mine waiting for a review?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(running);
        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Loading the repository…"));
        advance(SlackMentorProjector.PROGRESS_INTERVAL);
        verify(slack, times(1)).postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any());

        postsAt("200.1");
        when(slack.fetchHistoryPage(anyLong(), eq(CHANNEL), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(new HistoryPage(
                        List.of(
                                botMessage("500.8", null),
                                botMessage("500.9", SlackMentorPosts.progressMetadata(running.getId()))),
                        null));
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you make of the gap?"), "");
        changed(running);
        changed(running);

        verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.9");
        verify(slack, never()).deleteMessage(workspace.getId(), CHANNEL, "500.8");
    }

    @Test
    void shouldNotPostProgressAgainWhenSlackAcceptedThePostWithoutAReceipt() {
        projector = timedProjector();
        when(slack.postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "invalid_receipt"));
        ask(main, "Is anything of mine waiting for a review?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(running);
        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Loading the repository…"));
        advance(SlackMentorProjector.PROGRESS_INTERVAL);

        verify(slack, times(1)).postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any());
    }

    @Test
    void shouldWaitTheWholeRateLimitFromWhenSlackAnsweredBeforeShowingProgressAgain() {
        projector = timedProjector();
        doAnswer(call -> {
                    // The call itself takes five seconds before Slack answers with a 30 second limit.
                    clock.advance(Duration.ofSeconds(5));
                    throw new SlackSendException(workspace.getId(), CHANNEL, "ratelimited", 30_000);
                })
                .doReturn(new SlackMessageService.Posted(CHANNEL, "500.1"))
                .when(slack)
                .postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any());
        ask(main, "Is anything of mine waiting for a review?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, staged("Reading your pull requests…"));
        changed(running);
        advance(SlackMentorProjector.PROGRESS_NOTICE_AFTER);

        for (int seconds = 0; seconds < 28; seconds += 2) {
            advance(SlackMentorProjector.PROGRESS_INTERVAL);
        }
        verify(slack, times(1)).postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any());
        advance(SlackMentorProjector.PROGRESS_INTERVAL.multipliedBy(2));
        verify(slack, times(2)).postMessage(anyLong(), eq(CHANNEL), isNull(), eq(List.of()), anyString(), any());
    }

    @Test
    void shouldFindTheProgressOfAnEarlierAttemptAfterARestartWhenTheRetryWasShort() {
        MentorTurn asked =
                ask(main, "Is anything of mine waiting for a review?", "100.1").turn();
        MentorTurn first = turns.claimNext(WORKER, LEASE).orElseThrow();
        turns.renewLease(first.getId(), first.getAttempt(), WORKER, Duration.ofSeconds(-1));
        assertThat(turns.expireLease(first.getId())).isPresent();
        MentorTurn retried = turns.claimNext(WORKER, LEASE).orElseThrow();
        // A new process: it holds no receipt of the notice that the first attempt's server posted.
        projector = timedProjector();
        changed(retried);
        // Slack may end a page before the history ends: the notice is on the second page.
        when(slack.fetchHistoryPage(anyLong(), eq(CHANNEL), anyString(), anyString(), isNull(), anyInt()))
                .thenReturn(new HistoryPage(List.of(botMessage("500.8", null)), "page-2"));
        when(slack.fetchHistoryPage(anyLong(), eq(CHANNEL), anyString(), anyString(), eq("page-2"), anyInt()))
                .thenReturn(new HistoryPage(
                        List.of(botMessage("500.9", SlackMentorPosts.progressMetadata(retried.getId()))), null));
        postsAt("200.1");

        end(retried, MentorTurn.Status.DONE, linkingAnswer(retried, "What do you make of the gap?"), "");
        changed(turns.require(workspace.getId(), asked.getId()));
        changed(turns.require(workspace.getId(), asked.getId()));

        verify(slack).deleteMessage(workspace.getId(), CHANNEL, "500.9");
        verify(slack, never()).deleteMessage(workspace.getId(), CHANNEL, "500.8");
    }

    @Test
    void shouldPostEveryMessageAndTheFeedbackHephShowedOnceWhenATopLevelTurnEnds() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);

        verify(slack).setReaction(workspace.getId(), CHANNEL, "100.1", "eyes", true);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.PENDING);

        postsAt("200.1");
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you make of the gap?"), "");
        changed(running);
        changed(running);

        assertThat(postedTopLevel())
                .singleElement()
                .satisfies(text -> assertThat(text)
                        .startsWith("Here is what I saw.")
                        .contains("> " + FEEDBACK)
                        .endsWith("What do you make of the gap?"));
        verify(slack).setReaction(workspace.getId(), CHANNEL, "100.1", "eyes", false);
        assertThat(answerOf(running).getSlackTs()).isEqualTo("200.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(question(asked).getSlackTs()).isEqualTo("100.1");
        assertThat(feedbackState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(replies("delivered")).isOne();
    }

    @Test
    void shouldDeliverNoFeedbackWhoseTextSlackCannotShowWhenTheReplyIsCut() {
        MentorTurnService.Submission asked = ask(main, "Tell me everything", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        postsAt("200.1");

        List<JsonNode> entries = new ArrayList<>();
        entries.add(answer("a".repeat(13_000)));
        entries.addAll(linkingAnswer(running, "That is all."));
        end(running, MentorTurn.Status.DONE, entries, "");
        changed(running);

        assertThat(postedTopLevel())
                .singleElement()
                .satisfies(text -> assertThat(text).doesNotContain(FEEDBACK).endsWith(SlackMentorPosts.CUT_NOTE));
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.FAILED);
    }

    @Test
    void shouldLeaveFeedbackUnconfirmedWhenSlackCutsInsideItsOnlyQuote() {
        MentorTurnService.Submission asked = ask(main, "Tell me everything", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        postsAt("200.1");
        UUID feedback = ConversationFeedbackRows.claimedBy(
                jdbc, workspace.getId(), developerAccount, running.getId(), FEEDBACK);
        shownFeedback = feedback;
        String words = FEEDBACK + " " + "b".repeat(13_000);

        end(
                running,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.showFeedback(
                                MentorLedgerFixtures.piId(), "Here is what I saw.", Map.of("call-a", feedback), words),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-a", "show_feedback", "Shown", false),
                        answer("That is all.")),
                "");
        changed(running);

        assertThat(postedTopLevel())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("> " + FEEDBACK).endsWith(SlackMentorPosts.CUT_NOTE));
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState())
                .as("Slack showed the start of its only quote")
                .isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copySettled()).as("the receipt records it").isTrue();
    }

    @Test
    void shouldLeaveFeedbackUnconfirmedWhenSlackCutsTheSecondOfTheTwoQuotesThatShowIt() {
        MentorTurnService.Submission asked = ask(main, "Tell me everything", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        postsAt("200.1");
        UUID feedback = ConversationFeedbackRows.claimedBy(
                jdbc, workspace.getId(), developerAccount, running.getId(), FEEDBACK);
        shownFeedback = feedback;
        ArrayNode content = NODES.arrayNode();
        content.addObject().put("type", "text").put("text", "Here is what I saw.");
        for (Map.Entry<String, String> call :
                List.of(Map.entry("call-a", FEEDBACK), Map.entry("call-b", "b".repeat(13_000)))) {
            content.addObject()
                    .put("type", "toolCall")
                    .put("id", call.getKey())
                    .put("name", "show_feedback")
                    .putObject("arguments")
                    .put("feedbackId", feedback.toString())
                    .put("text", call.getValue());
        }

        end(
                running,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.message(MentorLedgerFixtures.piId(), "assistant", content),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-a", "show_feedback", "Shown", false),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-b", "show_feedback", "Shown", false),
                        answer("That is all.")),
                "");
        changed(running);

        assertThat(postedTopLevel())
                .singleElement()
                .satisfies(text -> assertThat(text).contains("> " + FEEDBACK).endsWith(SlackMentorPosts.CUT_NOTE));
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState())
                .as("Slack showed one of its quotes whole and cut the other")
                .isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copySettled()).as("the receipt records it").isTrue();
    }

    @Test
    void shouldStreamTheAnswerAndStopItUnderFeedbackButtonsWhenAThreadTurnRuns() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");

        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        assertThat(question(asked).getSlackStreamTs()).isEqualTo("300.1");
        writes(running, "Your reviews are quick.");
        end(running, MentorTurn.Status.DONE, "Your reviews are quick. Keep it up.", "");
        changed(running);

        MentorEntry answer = answerOf(running);
        verify(slack).setReaction(workspace.getId(), CHANNEL, "100.5", SlackMentorPosts.WORKING_EMOJI, true);
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " are quick.");
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " Keep it up.");
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(
                                null,
                                SlackMentorPosts.feedbackButtons(answer.getId()),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldFinishTheReplyOnceWhenAWakeUpForTheStreamIsStillPendingAtTheEnd() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        // Within the append interval: the stream waits for a wake-up.
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews are quick."));
        changed(running);
        assertThat(wakeUps).hasSize(1);

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);
        wakeUps.forEach(Runnable::run);

        verify(pendingWakeUp).cancel(false);
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " are quick.");
        verify(slack, times(1)).stopStream(anyLong(), anyString(), anyString(), any());
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(replies("delivered")).isOne();
    }

    @Test
    void shouldReplaceTheStreamWithTheWholeReplyWhenTheReconcilerFindsThatSlackAlreadyEndedIt() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is")).thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is"));
        changed(running);
        // The server stops before the turn ends; Slack ends the stream when it times out.
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        stopStreamFails("message_not_in_streaming_state");

        assertThat(delivery.retry(workspace.getId(), asked.question().getId())).isTrue();
        projector.reconcile(workspace.getId(), running.getId(), true);

        MentorEntry answer = answerOf(running);
        List<LayoutBlock> replacement = replacement(asked);
        assertThat(markdownOf(replacement))
                .startsWith("Here is what I saw.")
                .contains("> " + FEEDBACK)
                .endsWith("What do you think?");
        assertThat(replacement)
                .endsWith(SlackMentorPosts.feedbackButtons(answer.getId()).toArray(LayoutBlock[]::new));
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState())
                .as("the replaced message shows the feedback")
                .isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldReplaceTheStreamWithTheWholeReplyWhenSlackEndedItWhileHephWorked() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is")).thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is"));
        changed(running);
        // Slack ends an idle stream on its own, for example while Hephaestus waits for a tool.
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "message_not_in_streaming_state"));
        stopStreamFails("message_not_in_streaming_state");
        writes(running, "Here is what");
        writes(running, "Here is what I saw.");

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        MentorEntry answer = answerOf(running);
        List<LayoutBlock> replacement = replacement(asked);
        verify(slack, times(1)).appendStream(anyLong(), anyString(), anyString(), anyString());
        assertThat(markdownOf(replacement))
                .startsWith("Here is what I saw.")
                .contains("> " + FEEDBACK)
                .endsWith("What do you think?");
        assertThat(replacement)
                .endsWith(SlackMentorPosts.feedbackButtons(answer.getId()).toArray(LayoutBlock[]::new));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState())
                .as("the replaced message shows the feedback")
                .isEqualTo(FeedbackState.DELIVERED);
        assertThat(replies("delivered")).isOne();
    }

    @Test
    void shouldReplaceTheStreamWithTheWholeReplyWhenSlackEndedItBeforeTheRestOfTheAnswer() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is")).thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is"));
        changed(running);
        // The stream got no text while Hephaestus showed feedback, and Slack ended it meanwhile.
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "message_not_in_streaming_state"));

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        MentorEntry answer = answerOf(running);
        List<LayoutBlock> replacement = replacement(asked);
        assertThat(markdownOf(replacement))
                .startsWith("Here is what I saw.")
                .contains("> " + FEEDBACK)
                .endsWith("What do you think?");
        assertThat(replacement)
                .endsWith(SlackMentorPosts.feedbackButtons(answer.getId()).toArray(LayoutBlock[]::new));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(replies("delivered")).isOne();
    }

    @Test
    void shouldReplaceTheStreamWithTheWholeReplyWhenSlackRefusedEightAppendsInARow() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "ratelimited", 1_000L));
        for (int written = 1; written <= 9; written++) {
            writes(running, "Your reviews" + ".".repeat(written));
        }

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        MentorEntry answer = answerOf(running);
        verify(slack, times(8)).appendStream(anyLong(), anyString(), anyString(), anyString());
        assertThat(markdownOf(replacement(asked))).isEqualTo("Your reviews are quick.");
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldReplaceTheStreamThatAnUnansweredStartOpenedInsteadOfPostingBelowIt() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));
        String opened = SlackTs.ofInstant(clock.instant());
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        historyHolds(botMessage(opened, null));

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        opened,
                        new SlackMessageService.StreamStop(
                                null,
                                List.of(),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        verify(slack).updateMessage(eq(workspace.getId()), eq(CHANNEL), eq(opened), anyList(), anyString(), any());
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackStreamTs()).isEqualTo(opened);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldPostTheReplyAndOverwriteNoOtherMessageWhenARefusedStartOpenedNoStream() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        // While Hephaestus works, Hephaestus refuses another message in the thread with a reply of its own.
        clock.advance(Duration.ofSeconds(30));
        historyHolds(botMessage(SlackTs.ofInstant(clock.instant()), null));
        postsAt("400.1");

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        verify(slack, never()).updateMessage(anyLong(), anyString(), anyString(), anyList(), anyString(), any());
        verify(slack, never()).stopStream(anyLong(), anyString(), anyString(), any());
        verify(slack).postMessage(eq(workspace.getId()), eq(CHANNEL), eq("100.1"), anyList(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("400.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldReplaceTheStreamWhenHephsAnswerNoLongerStartsWithWhatSlackShows() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews ar"))
                .thenReturn("300.1");
        // A model call fails after Slack showed part of its text; the retried call writes another text.
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews ar"));
        changed(running);
        writes(running, "Let me look again.");

        end(running, MentorTurn.Status.DONE, "Let me look again. Your reviews are quick.", "");
        changed(running);

        verify(slack, never()).appendStream(anyLong(), anyString(), anyString(), anyString());
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(
                                null,
                                List.of(),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        assertThat(markdownOf(replacement(asked))).isEqualTo("Let me look again. Your reviews are quick.");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldReplaceADivergentStreamWithTheStoredAnswerWhenTheDeveloperStopsTheTurn() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews ar"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews ar"));
        changed(running);
        writes(running, "Let me look");

        turns.stopRunning(workspace.getId(), running.getConversationId());
        changed(running);
        end(running, MentorTurn.Status.STOPPED, "Let me look again.", "");
        changed(running);

        verify(slack, never()).appendStream(anyLong(), anyString(), anyString(), anyString());
        assertThat(markdownOf(replacement(asked))).isEqualTo("Let me look again.");
        assertThat(answerOf(running).getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldAskSlackAtMostOnceEachIntervalWhenSlackRefusesAppends() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "ratelimited", 1_000L));

        writes(running, "Your reviews are");
        // Later than the workspace's spacing, earlier than the stream's interval.
        clock.advance(SlackMentorProjector.WORKSPACE_APPEND_SPACING);
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews are quick."));
        changed(running);
        verify(slack, times(1)).appendStream(anyLong(), anyString(), anyString(), anyString());

        clock.advance(SlackMentorProjector.APPEND_INTERVAL);
        changed(running);
        verify(slack, times(2)).appendStream(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldReplaceTheStreamAndNeverPostTheAnswerTwiceWhenSlackLeavesAnAppendUnanswered() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "internal_error"));
        writes(running, "Your reviews are");
        writes(running, "Your reviews are quick.");

        end(running, MentorTurn.Status.DONE, "Your reviews are quick. Keep it up.", "");
        changed(running);

        MentorEntry answer = answerOf(running);
        verify(slack, times(1)).appendStream(anyLong(), anyString(), anyString(), anyString());
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(
                                null,
                                List.of(),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        List<LayoutBlock> replacement = replacement(asked);
        assertThat(markdownOf(replacement)).isEqualTo("Your reviews are quick. Keep it up.");
        assertThat(replacement)
                .endsWith(SlackMentorPosts.feedbackButtons(answer.getId()).toArray(LayoutBlock[]::new));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldLookForThePostAndNotTheStreamWhenThePostThatReplacedAGoneStreamIsUncertain() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "message_not_found"));
        writes(running, "Your reviews are quick.");
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
        assertThat(question(asked).getSlackStreamTs()).isNull();
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(CHANNEL), eq("100.1"), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("400.1"), false));
        assertThat(delivery.retry(workspace.getId(), asked.question().getId())).isTrue();
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).stopStream(anyLong(), anyString(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("400.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldSpaceTheAppendsOfTwoAnswersThatStreamInOneWorkspace() {
        MentorTurnService.Submission reviews = ask(main, "How are my reviews?", "100.1");
        MentorTurnService.Submission issues = ask(main, "How are my issues?", "100.2");
        MentorConversation reviewsThread =
                conversations.openThread(workspace.getId(), reviews.question().getId());
        MentorConversation issuesThread =
                conversations.openThread(workspace.getId(), issues.question().getId());
        MentorTurnService.Submission first = ask(reviewsThread, "Tell me more", "100.5");
        MentorTurnService.Submission second = ask(issuesThread, "Tell me more", "100.6");
        List<UUID> claimed = Stream.generate(() -> turns.claimNext(WORKER, LEASE))
                .limit(3)
                .flatMap(Optional::stream)
                .map(MentorTurn::getId)
                .toList();
        assertThat(claimed).contains(first.turn().getId(), second.turn().getId());
        MentorTurn one = running(first);
        MentorTurn two = running(second);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.2", "Your issues"))
                .thenReturn("300.2");
        turns.updatePartial(one.getId(), one.getAttempt(), WORKER, partial("Your reviews"));
        changed(one);
        turns.updatePartial(two.getId(), two.getAttempt(), WORKER, partial("Your issues"));
        changed(two);

        clock.advance(SlackMentorProjector.APPEND_INTERVAL);
        turns.updatePartial(one.getId(), one.getAttempt(), WORKER, partial("Your reviews are quick."));
        changed(one);
        turns.updatePartial(two.getId(), two.getAttempt(), WORKER, partial("Your issues are old."));
        changed(two);
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " are quick.");
        verify(slack, never()).appendStream(workspace.getId(), CHANNEL, "300.2", " are old.");

        clock.advance(SlackMentorProjector.WORKSPACE_APPEND_SPACING);
        wakeUps.forEach(Runnable::run);
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.2", " are old.");
    }

    @Test
    void shouldDeliverTheFeedbackTheStreamShowedWhenAThreadAnswerShowsIt() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is what I saw."))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw."));
        changed(running);

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you make of the gap?"), "");
        changed(running);

        ArgumentCaptor<String> appended = ArgumentCaptor.forClass(String.class);
        verify(slack).appendStream(eq(workspace.getId()), eq(CHANNEL), eq("300.1"), appended.capture());
        assertThat(appended.getValue()).contains(FEEDBACK).endsWith("What do you make of the gap?");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @ParameterizedTest
    @CsvSource({
        "STOPPED, false, POST",
        "STOPPED, true, POST",
        "FAILED, false, POST",
        "FAILED, true, POST",
        "STOPPED, false, STREAM",
        "STOPPED, true, STREAM",
        "STOPPED, false, SILENCED",
        "STOPPED, true, SILENCED",
        "STOPPED, false, DISCONNECTED",
        "STOPPED, true, DISCONNECTED",
        "FAILED, false, STREAM",
        "FAILED, true, STREAM",
        "FAILED, false, SILENCED",
        "FAILED, true, SILENCED",
        "FAILED, false, DISCONNECTED",
        "FAILED, true, DISCONNECTED"
    })
    void shouldKeepTheEndReceiptInEitherOrderWhenTheSlackReplyOfAnInterruptedTurnSettles(
            MentorTurn.Status outcome, boolean reverseReceipts, String path) {
        boolean topLevel = path.equals("POST");
        MentorTurnService.Submission asked = topLevel ? ask(main, "How are my reviews?", "100.1") : askInThread();
        MentorTurn running = topLevel ? turns.claimNext(WORKER, LEASE).orElseThrow() : running(asked);
        if (topLevel) {
            postsAt("300.1");
        }
        MentorTurnCompletion receiptListener = AopTestUtils.getUltimateTargetObject(completion);
        List<ConversationReceiptsDue> pending = new ArrayList<>();
        doAnswer(call -> {
                    ConversationReceiptsDue due = call.getArgument(0);
                    if (due.workspaceId() == workspace.getId() && due.turnId().equals(running.getId())) {
                        pending.add(due);
                    } else {
                        call.callRealMethod();
                    }
                    return null;
                })
                .when(receiptListener)
                .settle(any());
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is what I saw."))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw."));
        changed(running);
        List<JsonNode> answer = linkingAnswer(running, "What do you make of the gap?");
        UUID unshown = ConversationFeedbackRows.claimedBy(
                jdbc, workspace.getId(), developerAccount, running.getId(), "Feedback Hephaestus did not show.");
        if (outcome == MentorTurn.Status.STOPPED) {
            turns.stop(workspace.getId(), running.getId());
        }
        end(running, outcome, answer, outcome == MentorTurn.Status.FAILED ? "Hephaestus could not finish." : "");
        if (path.equals("DISCONNECTED")) {
            when(slack.isConnected(workspace.getId())).thenReturn(false);
            assertThat(delivery.retry(workspace.getId(), asked.question().getId()))
                    .isTrue();
            projector.reconcile(workspace.getId(), running.getId(), true);
        } else {
            if (path.equals("SILENCED")) {
                stopStreamFails("silent_mode_engaged");
            }
            changed(running);
        }
        // The listeners may run in either order in production, outside the turn's committing transaction.
        doCallRealMethod().when(receiptListener).settle(any());
        (reverseReceipts ? pending.reversed() : pending).forEach(completion::settle);

        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(jdbc.queryForObject(
                        "SELECT state FROM feedback WHERE workspace_id=? AND id=?",
                        String.class,
                        workspace.getId(),
                        unshown))
                .isEqualTo(FeedbackState.FAILED.name());
        assertThat(copySettled()).isTrue();
        assertThat(pending).hasSize(1);
    }

    @Test
    void shouldStopOnlyTheStreamingTurnWhenSlackReportsThatTheDeveloperStoppedIt() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        MentorTurnService.Submission followUp =
                ask(conversations.require(workspace.getId(), running.getConversationId()), "And next?", "100.6");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is what I saw."))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw."));
        changed(running);
        appendFails(new SlackSendException(workspace.getId(), CHANNEL, "stopped_by_user"));
        clock.advance(SlackMentorProjector.APPEND_INTERVAL);
        List<JsonNode> answer = linkingAnswer(running, "What do you make of the gap?");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw. More"));

        changed(running);

        assertThat(turns.require(workspace.getId(), running.getId()).getStatus())
                .isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turns.require(workspace.getId(), followUp.turn().getId()).getStatus())
                .isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.PENDING);
        end(running, MentorTurn.Status.STOPPED, answer, "");
        changed(running);
        changed(running);

        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " More");
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        verify(slack, never()).stopStream(anyLong(), anyString(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("300.1");
        // The successful show_feedback call preceded the stop; its visibility outside Slack is not disproved.
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldShowTheThreadWorkingWhileItsTurnRunsAndEndItWhenNoOtherSlackWorkRuns() {
        recordSessionCalls();
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");

        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        assertThat(sessionCalls).containsExactly("status:processing");
        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        assertThat(sessionCalls).containsExactly("status:processing", "stop:300.1:active", "status:active");
        verify(slack).setSessionStatus(workspace.getId(), CHANNEL, "100.1", true);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldEndTheSessionWhenOnlyAQueuedFollowUpWaits() {
        recordSessionCalls();
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        MentorTurnService.Submission followUp =
                ask(conversations.require(workspace.getId(), running.getConversationId()), "And next?", "100.6");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        assertThat(turns.require(workspace.getId(), followUp.turn().getId()).getStatus())
                .isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(sessionCalls).containsExactly("status:processing", "stop:300.1:active", "status:active");
    }

    @Test
    void shouldKeepTheThreadWorkingWhenTheNextTurnRunsBeforeTheEarlierStreamEnds() {
        recordSessionCalls();
        MentorTurnService.Submission asked = askInThread();
        MentorTurn first = running(asked);
        MentorTurnService.Submission next =
                ask(conversations.require(workspace.getId(), first.getConversationId()), "And next?", "100.6");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(first.getId(), first.getAttempt(), WORKER, partial("Your reviews"));
        changed(first);
        end(first, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        MentorTurn second = turns.claimNext(WORKER, LEASE).orElseThrow();
        assertThat(second.getId()).isEqualTo(next.turn().getId());
        changed(second);

        changed(first);

        assertThat(sessionCalls)
                .containsExactly(
                        "status:processing", "status:processing", "stop:300.1:processing", "status:processing");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    /**
     * Two servers: one ends the earlier turn's stream while Slack holds the call, the other starts the next turn
     * meanwhile. The claim does not wait for Slack, but the next turn's session call waits for the end of the stream,
     * so Slack takes it last and the thread shows the next turn working.
     */
    @Test
    void shouldKeepTheNextTurnWorkingWhenItsSessionCallWaitsForTheEndOfTheEarlierStream() throws Exception {
        recordSessionCalls();
        MentorTurnService.Submission asked = askInThread();
        MentorTurn first = running(asked);
        MentorTurnService.Submission next =
                ask(conversations.require(workspace.getId(), first.getConversationId()), "And next?", "100.6");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(first.getId(), first.getAttempt(), WORKER, partial("Your reviews"));
        changed(first);
        CountDownLatch inSlack = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger endingPid = new AtomicInteger();
        doAnswer(call -> {
                    endingPid.set(
                            Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    recordStop(call.getArgument(2), call.getArgument(3));
                    inSlack.countDown();
                    assertThat(answer.await(20, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(slack)
                .stopStream(anyLong(), anyString(), anyString(), any());
        end(first, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        CompletableFuture<Void> ending = CompletableFuture.runAsync(() -> changed(first));
        CompletableFuture<Void> starting = null;
        try {
            assertThat(inSlack.await(20, TimeUnit.SECONDS)).isTrue();

            MentorTurn second = turns.claimNext(WORKER, LEASE).orElseThrow();
            assertThat(second.getId()).isEqualTo(next.turn().getId());
            starting = CompletableFuture.runAsync(() -> changed(second));
            Awaitility.await().atMost(20, TimeUnit.SECONDS).until(() -> blocksAnother(endingPid.get()));
            assertThat(sessionCalls).containsExactly("status:processing", "stop:300.1:active");
        } finally {
            // Slack answers the held call even when an assertion above failed, so no server is left waiting.
            answer.countDown();
            ending.get(20, TimeUnit.SECONDS);
            if (starting != null) {
                starting.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(sessionCalls.subList(0, 2)).containsExactly("status:processing", "stop:300.1:active");
        assertThat(sessionCalls.subList(2, sessionCalls.size())).isNotEmpty().containsOnly("status:processing");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    /** The developer's identity as the person-data write fence and an erasure name it. */
    private List<PersonIdentity> developerIdentity() {
        return List.of(new PersonIdentity(
                Objects.requireNonNull(developer.getProvider().getId()),
                developer.getNativeId().toString(),
                null));
    }

    /** A session call of the Hephaestus thread on {@code 100.1} that only reports that it ran. */
    private Optional<String> sessionCall(UUID threadId, AtomicBoolean called) {
        return delivery.callInThreadSession(workspace.getId(), threadId, CHANNEL, "100.1", session -> {
            called.set(true);
            return "called";
        });
    }

    /** Whether a transaction waits at the person-data fence (an advisory lock) that the one on {@code pid} holds. */
    private boolean waitsAtTheFenceOf(int pid) {
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return pid != 0
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS (SELECT FROM pg_stat_activity"
                                + " WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event = 'advisory')",
                        Boolean.class,
                        pid));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @ParameterizedTest
    @CsvSource({"erased", "deleting"})
    void shouldMakeNoSessionCallForADeveloperThePersonDataFenceRefuses(String refusal) {
        MentorTurnService.Submission asked = askInThread();
        UUID threadId = running(asked).getConversationId();
        AtomicBoolean admitted = new AtomicBoolean();
        assertThat(sessionCall(threadId, admitted)).contains("called");
        assertThat(admitted).isTrue();
        if (refusal.equals("erased")) {
            PersonIdentity identity = developerIdentity().getFirst();
            databaseTestUtils.erase(identity.providerId(), identity.subject(), null);
        } else {
            jdbc.update("UPDATE account SET status = 'DELETING' WHERE id = ?", developerAccount);
        }
        AtomicBoolean refused = new AtomicBoolean();

        assertThat(sessionCall(threadId, refused)).isEmpty();

        assertThat(refused).isFalse();
    }

    /**
     * An erasure holds the developer's person-data fence. A session call waits for it at the fence, before it shares
     * the main conversation's row or locks the thread's row, and runs once the erasure's transaction ends.
     */
    @Test
    void shouldWaitForAnErasureAtThePersonDataFenceBeforeItLocksTheConversation() throws Exception {
        MentorTurnService.Submission asked = askInThread();
        UUID threadId = running(asked).getConversationId();
        CountDownLatch fenced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger eraserPid = new AtomicInteger();
        AtomicBoolean called = new AtomicBoolean();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> erasing = executor.submit(() -> transactions.executeWithoutResult(status -> {
                eraserPid.set(Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                writeFence.holdForErasure(developerIdentity());
                fenced.countDown();
                awaitRelease(release);
            }));
            Future<Optional<String>> session = null;
            try {
                assertThat(fenced.await(20, TimeUnit.SECONDS)).isTrue();
                session = executor.submit(() -> sessionCall(threadId, called));
                Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> waitsAtTheFenceOf(eraserPid.get()));
                // The waiting call holds no conversation row: another transaction locks both at once.
                var lockedRows = Objects.requireNonNull(transactions.execute(status -> jdbc.queryForList(
                        "SELECT id FROM mentor_conversation WHERE id IN (?, ?) FOR UPDATE NOWAIT",
                        UUID.class,
                        main.getId(),
                        threadId)));
                assertThat(lockedRows).containsExactlyInAnyOrder(main.getId(), threadId);
                assertThat(called).isFalse();
            } finally {
                release.countDown();
                erasing.get(20, TimeUnit.SECONDS);
            }
            assertThat(Objects.requireNonNull(session).get(20, TimeUnit.SECONDS))
                    .contains("called");
            assertThat(called).isTrue();
        } finally {
            release.countDown();
        }
    }

    /**
     * A session call holds the fence, then the conversation's rows, while Slack holds its call. An erasure waits at the
     * fence, not at a row, so it never holds a row that the session call waits for; both end once Slack answers.
     */
    @Test
    void shouldLetAnErasureWaitAtTheFenceWhileASessionCallIsInSlack() throws Exception {
        MentorTurnService.Submission asked = askInThread();
        UUID threadId = running(asked).getConversationId();
        CountDownLatch inSlack = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger sessionPid = new AtomicInteger();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<String>> session = executor.submit(() ->
                    delivery.callInThreadSession(workspace.getId(), threadId, CHANNEL, "100.1", threadSession -> {
                        sessionPid.set(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        inSlack.countDown();
                        awaitRelease(answer);
                        return "called";
                    }));
            Future<List<UUID>> erasing = null;
            try {
                assertThat(inSlack.await(20, TimeUnit.SECONDS)).isTrue();
                erasing = executor.submit(() -> transactions.execute(status -> {
                    writeFence.holdForErasure(developerIdentity());
                    return jdbc.queryForList(
                            "SELECT id FROM mentor_conversation WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
                            UUID.class,
                            main.getId(),
                            threadId);
                }));
                Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> waitsAtTheFenceOf(sessionPid.get()));
            } finally {
                answer.countDown();
            }
            assertThat(session.get(20, TimeUnit.SECONDS)).contains("called");
            assertThat(Objects.requireNonNull(erasing).get(20, TimeUnit.SECONDS))
                    .containsExactlyInAnyOrder(main.getId(), threadId);
        } finally {
            answer.countDown();
        }
    }

    @Test
    void shouldNeverSetASessionStatusForATopLevelTurn() {
        postsAt("400.1");
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        verify(slack, never()).setSessionStatus(anyLong(), anyString(), anyString(), anyBoolean());
        verify(slack, never()).stopStream(anyLong(), anyString(), anyString(), any());
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldDeliverTheStreamedAnswerAsBeforeWhenSlackRefusesTheSessionStatus() {
        doThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"))
                .when(slack)
                .setSessionStatus(anyLong(), anyString(), anyString(), anyBoolean());
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);

        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        changed(running);

        MentorEntry answer = answerOf(running);
        verify(slack).appendStream(workspace.getId(), CHANNEL, "300.1", " are quick.");
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(
                                null,
                                SlackMentorPosts.feedbackButtons(answer.getId()),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldDeliverNoFeedbackWhenSlackStoppedTheStreamBeforeItsText() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is what I saw."))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw."));
        changed(running);
        doThrow(new SlackSendException(workspace.getId(), CHANNEL, "stopped_by_user"))
                .when(slack)
                .appendStream(anyLong(), anyString(), anyString(), anyString());

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you make of the gap?"), "");
        changed(running);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.FAILED);
    }

    @Test
    void shouldKeepWhatTheStreamShowsAndAppendNothingWhenTheDeveloperStoppedTheTurn() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Your reviews"))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Your reviews"));
        changed(running);
        stopStreamFails("message_not_in_streaming_state");

        turns.stopRunning(workspace.getId(), running.getConversationId());
        changed(running);
        end(running, MentorTurn.Status.STOPPED, "Your reviews are quick", "");
        changed(running);

        verify(slack, never()).appendStream(anyLong(), anyString(), anyString(), anyString());
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        verify(slack).setReaction(workspace.getId(), CHANNEL, "100.5", SlackMentorPosts.WORKING_EMOJI, false);
        assertThat(answerOf(running).getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
    }

    @Test
    void shouldEndTheStreamInPlaceAndNeverPostItAgainWhenSlackDoesNotAnswerTheEndOfTheStream() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is")).thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is"));
        changed(running);
        stopStreamFails("ratelimited");

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(replies("uncertain")).isOne();

        doNothing().when(slack).stopStream(anyLong(), anyString(), anyString(), any());
        assertThat(delivery.retry(workspace.getId(), asked.question().getId())).isTrue();
        projector.reconcile(workspace.getId(), running.getId(), true);

        MentorEntry answer = answerOf(running);
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(
                                SlackMentorPosts.CUT_NOTE,
                                SlackMentorPosts.feedbackButtons(answer.getId()),
                                SlackMentorPosts.metadata(asked.question().getId()),
                                false));
        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        verify(slack, never()).updateMessage(anyLong(), anyString(), anyString(), anyList(), anyString(), any());
        assertThat(answer.getSlackTs()).isEqualTo("300.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState())
                .as("what the stream showed is unknown to the reconciler")
                .isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldDeliverTheFeedbackTheStreamShowedWhenSilentModeRefusesTheEndOfTheStream() {
        MentorTurnService.Submission asked = askInThread();
        MentorTurn running = running(asked);
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Here is what I saw."))
                .thenReturn("300.1");
        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, partial("Here is what I saw."));
        changed(running);
        stopStreamFails("silent_mode_engaged");

        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.FAILED);
        assertThat(feedbackState())
                .as("the stream showed the feedback before Silent Mode refused its end")
                .isEqualTo(FeedbackState.DELIVERED);
        assertThat(replies("failed")).isOne();
        assertThat(replies("delivered")).isZero();
    }

    @Test
    void shouldStreamTheRetriedAttemptAfreshWhenTheFirstAttemptLostItsLease() {
        MentorTurnService.Submission parent = ask(main, "How are my reviews?", "100.1");
        MentorConversation thread =
                conversations.openThread(workspace.getId(), parent.question().getId());
        MentorTurnService.Submission asked = ask(thread, "Tell me more", "100.5");
        turns.claimNext(WORKER, LEASE);
        MentorTurn first = turns.claimNext(WORKER, Duration.ofSeconds(-1)).orElseThrow();
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "First try"))
                .thenReturn("300.1");
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Second try"))
                .thenReturn("300.2");
        turns.updatePartial(first.getId(), first.getAttempt(), WORKER, partial("First try"));
        changed(first);

        completion.billLostAttempt(turns.expireLease(first.getId()).orElseThrow());
        changed(first);
        MentorTurn retried = turns.claimNext(WORKER, LEASE).orElseThrow();
        writes(retried, "Second try");

        assertThat(retried.getAttempt()).isEqualTo(2);
        verify(slack)
                .stopStream(
                        workspace.getId(),
                        CHANNEL,
                        "300.1",
                        new SlackMessageService.StreamStop(SlackMentorPosts.RESTART_NOTE, List.of(), null, false));
        verify(slack).startStream(workspace.getId(), CHANNEL, "100.1", "Second try");
        assertThat(question(asked).getSlackStreamTs()).isEqualTo("300.2");
    }

    @Test
    void shouldStreamTheRetriedAttemptWhenTheLostAttemptCouldNotOpenItsStream() {
        MentorTurnService.Submission parent = ask(main, "How are my reviews?", "100.1");
        MentorConversation thread =
                conversations.openThread(workspace.getId(), parent.question().getId());
        MentorTurnService.Submission asked = ask(thread, "Tell me more", "100.5");
        turns.claimNext(WORKER, LEASE);
        MentorTurn first = turns.claimNext(WORKER, Duration.ofSeconds(-1)).orElseThrow();
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "First try"))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));
        when(slack.startStream(workspace.getId(), CHANNEL, "100.1", "Second try"))
                .thenReturn("300.2");
        turns.updatePartial(first.getId(), first.getAttempt(), WORKER, partial("First try"));
        changed(first);

        completion.billLostAttempt(turns.expireLease(first.getId()).orElseThrow());
        changed(first);
        MentorTurn retried = turns.claimNext(WORKER, LEASE).orElseThrow();
        writes(retried, "Second try");

        verify(slack).startStream(workspace.getId(), CHANNEL, "100.1", "Second try");
        assertThat(question(asked).getSlackStreamTs()).isEqualTo("300.2");
    }

    @Test
    void shouldReplyWithTheReasonAndDeliverNoFeedbackWhenTheTurnFails() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        postsAt("200.9");
        end(
                running,
                MentorTurn.Status.FAILED,
                linkingAnswer(running, "unused"),
                "Hephaestus took too long to reply and stopped. Try again.");
        changed(running);

        verify(slack)
                .postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        isNull(),
                        anyList(),
                        eq("Hephaestus took too long to reply and stopped. Try again."),
                        eq(SlackMentorPosts.metadata(asked.question().getId())));
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(question(asked).getSlackTs()).isEqualTo("100.1");
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldPostNothingAndCountNoReplyWhenTheDeveloperStoppedATopLevelTurnBeforeHephWrote() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);

        turns.stopRunning(workspace.getId(), main.getId());
        end(running, MentorTurn.Status.STOPPED, "", "");
        changed(running);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        verify(slack).setReaction(workspace.getId(), CHANNEL, "100.1", "eyes", false);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.FAILED);
        assertThat(meters.find("mentor.slack.reply").counters()).isEmpty();
    }

    @Test
    void shouldDeliverNoFeedbackWhenSlackRefusesTheAnswer() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "channel_not_found"));
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.FAILED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.FAILED);
    }

    @Test
    void shouldLeaveThePostUncertainForTheReconcilerWhenSlackDoesNotAnswer() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        when(slack.postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "transport_failure"));
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        changed(running);

        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
        Instant later = Instant.now().plus(SlackMentorReconciler.GRACE).plusSeconds(60);
        assertThat(delivery.due(
                        later.minus(Duration.ofHours(1)),
                        later,
                        SlackMentorReconciler.GRACE,
                        SlackMentorReconciler.MAX_ATTEMPTS,
                        5,
                        1_000))
                .contains(new MentorSlackDelivery.Unsettled(
                        workspace.getId(), asked.question().getId(), running.getId(), true));
    }

    @Test
    void shouldTakeAnUnsettledReplyOnceAndLeaveItUncertainWhenTheReconcilerCouldNotDecide() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running); // claimed and reacted, then the server died
        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        UUID questionId = asked.question().getId();
        MentorSlackDelivery.Unsettled unsettled =
                new MentorSlackDelivery.Unsettled(workspace.getId(), questionId, running.getId(), true);
        Duration delay = Duration.ofMinutes(2);
        Instant ended = Objects.requireNonNull(running(asked).getFinishedAt());

        assertThat(delivery.due(ended.minusSeconds(1), ended.plus(delay).plusSeconds(1), delay, 1, 5, 1_000))
                .contains(unsettled);
        assertThat(delivery.retry(workspace.getId(), questionId)).isTrue();
        assertThat(delivery.due(ended.minusSeconds(1), ended.plus(Duration.ofDays(1)), delay, 1, 5, 1_000))
                .as("one history read decides, or nothing does")
                .doesNotContain(unsettled);
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
    }

    @Test
    void shouldTakeAtMostItsShareFromOneWorkspaceWhenManyRepliesAreUnsettled() {
        List<UUID> questions = new ArrayList<>();
        for (String ts : List.of("100.1", "100.2", "100.3")) {
            MentorTurnService.Submission asked = ask(main, "Question " + ts, ts);
            MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
            end(running, MentorTurn.Status.DONE, "Answer " + ts, "");
            questions.add(asked.question().getId());
        }
        Instant later = Instant.now().plus(Duration.ofMinutes(10));

        List<MentorSlackDelivery.Unsettled> due =
                delivery.due(later.minus(Duration.ofHours(1)), later, Duration.ofMinutes(2), 8, 2, 1_000);

        assertThat(due)
                .filteredOn(unsettled -> questions.contains(unsettled.entryId()))
                .extracting(MentorSlackDelivery.Unsettled::entryId)
                .hasSize(2)
                .containsExactly(questions.get(0), questions.get(1));
    }

    @Test
    void shouldSettleAnUncertainPostFromTheSlackHistoryWhenTheReconcilerFindsIt() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running); // claimed and reacted, then the server died
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(CHANNEL), isNull(), eq("100.1"), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("200.9"), false));

        assertThat(delivery.retry(workspace.getId(), asked.question().getId())).isTrue();
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("200.9");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldFailTheAnswerAndItsFeedbackAndNeverPostItAgainWhenTheSlackHistoryDoesNotHoldIt() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), false));

        delivery.retry(workspace.getId(), asked.question().getId());
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery())
                .as("the app shows the answer as not delivered to Slack")
                .isEqualTo(MentorEntry.SlackDelivery.FAILED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.FAILED);
    }

    @Test
    void shouldFailTheAnswerAndLeaveItsFeedbackUnconfirmedWhenSlackIsDisconnectedBeforeTheReconcilerLooks() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        when(slack.isConnected(workspace.getId())).thenReturn(false);

        delivery.retry(workspace.getId(), asked.question().getId());
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery())
                .as("the app shows the answer as not delivered to Slack")
                .isEqualTo(MentorEntry.SlackDelivery.FAILED);
        assertThat(feedbackState())
                .as("a server took the reply, so Slack may show the feedback: it is never sent again")
                .isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copySettled())
                .as("the receipt records the unconfirmed answer")
                .isTrue();
    }

    @Test
    void shouldLeaveTheAnswerUncertainAndItsFeedbackUnconfirmedWhenTheSlackHistoryCannotBeRead() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                .thenThrow(new SlackSendException(workspace.getId(), CHANNEL, "ratelimited"));

        delivery.retry(workspace.getId(), asked.question().getId());
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copySettled())
                .as("the receipt records the unconfirmed answer")
                .isTrue();
    }

    @Test
    void shouldSendAReplyThatNoServerTookWhenTheReconcilerFindsIt() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        // No server projected the turn: the question was never taken.
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        postsAt("201.1");

        assertThat(delivery.claim(workspace.getId(), asked.question().getId())).isTrue();
        projector.reconcile(workspace.getId(), running.getId(), false);

        verify(slack, never()).readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any());
        verify(slack, times(1)).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(answerOf(running).getSlackTs()).isEqualTo("201.1");
        assertThat(question(asked).getSlackDelivery()).isEqualTo(MentorEntry.SlackDelivery.DELIVERED);
        assertThat(feedbackState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldNeverTouchSlackWhenTheQuestionWasAskedInTheApp() {
        turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "From the app",
                        UUID.randomUUID().toString(),
                        null,
                        null));
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, "An answer in the app.", "");
        changed(running);

        verifyNoInteractions(slack);
    }

    @Test
    void shouldLeaveTheAnswerUncertainAndItsFeedbackUnconfirmedWhenTheSlackHistoryHoldsMoreThanThePageWithoutIt() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        changed(running);
        end(running, MentorTurn.Status.DONE, linkingAnswer(running, "What do you think?"), "");
        // The developer wrote more messages after the answer than one page holds.
        when(slack.readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), true));

        delivery.retry(workspace.getId(), asked.question().getId());
        projector.reconcile(workspace.getId(), running.getId(), true);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery())
                .as("a page without the answer does not prove that Slack does not hold it")
                .isEqualTo(MentorEntry.SlackDelivery.UNCERTAIN);
        assertThat(feedbackState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(copySettled())
                .as("the receipt records the unconfirmed answer")
                .isTrue();
    }

    @Test
    void shouldNeverSendAReplyAgainWhenAServerClaimsItBetweenTheReconcilerSelectionAndItsTake() {
        MentorTurnService.Submission asked = ask(main, "How are my reviews?", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        // No server projected the turn yet: the question is not taken.
        end(running, MentorTurn.Status.DONE, "Your reviews are quick.", "");
        UUID questionId = asked.question().getId();
        Instant now = Instant.now().plus(Duration.ofMinutes(5));
        MentorSlackDelivery.Unsettled untaken =
                new MentorSlackDelivery.Unsettled(workspace.getId(), questionId, running.getId(), false);
        List<MentorSlackDelivery.Unsettled> selected = delivery
                .due(
                        now.minus(SlackMentorReconciler.HORIZON),
                        now,
                        SlackMentorReconciler.GRACE,
                        SlackMentorReconciler.MAX_ATTEMPTS,
                        5,
                        1_000)
                .stream()
                .filter(untaken::equals)
                .toList();
        assertThat(selected).containsExactly(untaken);
        MentorSlackDelivery interleaved = mock(MentorSlackDelivery.class);
        when(interleaved.due(any(), any(), any(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            // A delayed projector claims the reply after the reconciler selected it as untaken.
            assertThat(delivery.claim(workspace.getId(), questionId)).isTrue();
            return selected;
        });
        when(interleaved.claim(workspace.getId(), questionId))
                .thenAnswer(call -> delivery.claim(workspace.getId(), questionId));
        when(interleaved.retry(workspace.getId(), questionId))
                .thenAnswer(call -> delivery.retry(workspace.getId(), questionId));
        postsAt("201.1");

        new SlackMentorReconciler(
                        interleaved, projector, mock(SlackWeeklyDigestPointer.class), Clock.fixed(now, ZoneOffset.UTC))
                .reconcile();

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(question(asked).getSlackDelivery())
                .as("the server that claimed the reply still owns it")
                .isEqualTo(MentorEntry.SlackDelivery.PENDING);
    }
}
