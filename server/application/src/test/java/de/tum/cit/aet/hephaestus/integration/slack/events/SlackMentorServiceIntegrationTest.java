package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveConnectionGuard;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationMessageDispatcher;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorPosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.nats.client.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** What the developer does in their Slack DM with Hephaestus, and the ledger rows it writes. */
class SlackMentorServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";
    private static final String WORKER = "worker-slack-ingress";
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private IntegrationMessageDispatcher dispatcher;

    @Autowired
    private SlackSubjectKeyDeriver subjectDeriver;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private ActiveConnectionGuard activeConnections;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTimeline timeline;

    @Autowired
    private MentorSlackDelivery delivery;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private SlackWorkspaceContentEraser eraser;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final SlackMentorIdentityResolver identities = mock(SlackMentorIdentityResolver.class);
    private final MentorReadinessQuery readiness = mock(MentorReadinessQuery.class);
    private final MentorAdmission admission = mock(MentorAdmission.class);

    private Workspace workspace;
    private Workspace otherWorkspace;
    private long developerId;
    private SlackMentorService service;

    @BeforeEach
    void setUpSlack() {
        User developer = persistUser("slack-heph-developer");
        developerId = accountId(developer);
        workspace = createWorkspace("slack-heph", "Slack Hephaestus", "slack-heph", AccountType.ORG, developer);
        otherWorkspace =
                createWorkspace("slack-heph-2", "Slack Hephaestus 2", "slack-heph-2", AccountType.ORG, developer);
        for (Workspace each : List.of(workspace, otherWorkspace)) {
            String team = each.getId().equals(workspace.getId()) ? TEAM : OTHER_TEAM;
            Connection slackConnection = new Connection(
                    each, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, "Slack", null, Set.of()));
            slackConnection.setState(IntegrationState.ACTIVE);
            connectionRepository.save(slackConnection);
            when(identities.resolveActiveAccountId(each.getId(), team, SLACK_USER))
                    .thenReturn(Optional.of(developerId));
            when(readiness.isReady(each.getId())).thenReturn(true);
            when(admission.refusal(each.getId(), developerId)).thenReturn(Optional.empty());
        }
        service = new SlackMentorService(
                new SlackWorkspaceResolver(connectionService, activeConnections),
                slack,
                identities,
                new KeywordSlackMentorInputGuard(),
                mock(SlackOnboardingService.class),
                readiness,
                admission,
                conversations,
                entries,
                turns,
                timeline);
    }

    private MentorConversation main() {
        return conversationRepository.findMain(workspace.getId(), developerId).orElseThrow();
    }

    private MentorEntry question(String ts) {
        return entryRepository
                .findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts)
                .orElseThrow();
    }

    private Optional<MentorEntry> maybeQuestion(String ts) {
        return entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts);
    }

    /** Nothing of the DM reached the ledger: no conversation, no question. */
    private void assertNothingStored(String ts) {
        assertThat(conversationRepository.findMain(workspace.getId(), developerId))
                .isEmpty();
        assertThat(maybeQuestion(ts)).isEmpty();
    }

    private MentorTurn turnOf(MentorEntry question) {
        return turnRepository
                .findById(Objects.requireNonNull(question.getTurnId()))
                .orElseThrow();
    }

    /** Runs the main conversation's turn to an answer that Slack shows at {@code ts}. */
    private MentorEntry answerPostedAt(String ts) {
        MentorTurn running = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        ObjectNode pi = MentorLedgerFixtures.answer("a1b2c3d4", "Your reviews are quick.");
        turns.end(running.getId(), running.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(pi), null);
        UUID answerId = Objects.requireNonNull(
                turns.answer(workspace.getId(), running.getId()).answerEntryId());
        delivery.delivered(
                workspace.getId(),
                entryRepository.findQuestion(workspace.getId(), running.getId()).getId(),
                answerId,
                ts);
        return entryRepository.findById(answerId).orElseThrow();
    }

    /** A message of the Slack stream on {@code subject}, as the consumer hands it to a handler. */
    private static Message natsMessage(String subject, String body) {
        Message message = mock(Message.class);
        when(message.getSubject()).thenReturn(subject);
        when(message.getData()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return message;
    }

    /**
     * The Hephaestus thread on the question {@code 100.1}, whose turn ended with an answer at {@code 100.2}: the reply
     * {@code 100.5} runs on a lease of {@code lease}, and the follow-up {@code 100.6} waits behind it.
     *
     * @return the running turn as the ledger stores it
     */
    private MentorTurn runningThreadTurnWithFollowUp(Duration lease) {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        answerPostedAt("100.2");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And the tests?", "100.6", "100.1");
        MentorTurn claimed = turns.claimNext(WORKER, lease).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(question("100.5").getTurnId());
        MentorTurn running = turnOf(question("100.5"));
        assertThat(running.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(turnOf(question("100.6")).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        return running;
    }

    /** The database clock now, as a Slack {@code ts}, checked to be after the start of {@code running}. */
    private String afterStartOf(MentorTurn running) {
        Instant now = turnRepository.databaseClock();
        assertThat(now).isAfter(Objects.requireNonNull(running.getStartedAt()));
        return SlackTs.ofInstant(now);
    }

    /** A signed Slack envelope of {@code team} that carries an {@code agent_session_stopped} event with {@code fields}. */
    private static String sessionStopped(String team, String fields) {
        return """
                {"type":"event_callback","team_id":"%s","event_id":"Ev0SESSIONSTOP","event":{
                  "type":"agent_session_stopped",%s}}
                """.formatted(team, fields);
    }

    private static String stopFields(String user, String channel, String threadTs, String eventTs, String streams) {
        return """
                "user":"%s","channel":"%s","thread_ts":"%s","event_ts":"%s","streaming_message_ts":%s""".formatted(user, channel, threadTs, eventTs, streams);
    }

    /** The developer stops the session of the Slack thread on {@code 100.1} in their own DM. */
    private static String ownThreadStopped(String eventTs, String streams) {
        return sessionStopped(TEAM, stopFields(SLACK_USER, CHANNEL, "100.1", eventTs, streams));
    }

    /** Hands {@code envelope} to the handler that the real dispatcher finds for its subject. */
    private void deliver(String envelope) {
        String subject = subjectDeriver.subjectFor(JSON.readTree(envelope));
        Optional<IntegrationMessageHandler> handler = dispatcher.dispatch(subject);
        assertThat(handler)
                .as("a handler for the subscribed agent_session_stopped event")
                .isPresent();
        handler.orElseThrow().onMessage(natsMessage(subject, envelope));
    }

    private MentorTurn.Status statusOf(String questionTs) {
        return turnOf(question(questionTs)).getStatus();
    }

    /** The thread's reply still runs, its follow-up waits, and the answered main turn stays done. */
    private void assertNothingStopped() {
        assertThat(statusOf("100.5")).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(statusOf("100.6")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    /** Only the thread's running reply stopped: its follow-up waits, and the answered main turn stays done. */
    private void assertOnlyTheRunningReplyStopped() {
        assertThat(statusOf("100.5")).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(statusOf("100.6")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    @Test
    void shouldStoreAQuestionInTheMainConversationWhenTheDeveloperWritesATopLevelDm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        MentorConversation main = main();
        assertThat(main.getSlackTeamId()).isEqualTo(TEAM);
        assertThat(main.getSlackUserId()).isEqualTo(SLACK_USER);
        assertThat(main.getSlackChannelId()).isEqualTo(CHANNEL);
        MentorEntry question = question("100.1");
        assertThat(question.getConversationId()).isEqualTo(main.getId());
        assertThat(question.getKind()).isEqualTo(MentorEntry.Kind.USER);
        assertThat(question.getSurface()).isEqualTo(MentorEntry.Surface.SLACK);
        assertThat(question.getSlackTs()).isEqualTo("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        MentorTurn turn = turnOf(question);
        assertThat(turn.getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(turn.getSurface()).isEqualTo(MentorTurn.Surface.SLACK);
    }

    @Test
    void shouldStoreOneQuestionWhenSlackDeliversTheSameDmTwice() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        MentorEntry question = question("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .hasValueSatisfying(turn -> assertThat(turn.getId()).isEqualTo(question.getTurnId()));
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
    }

    @Test
    void shouldStoreTheReplyInTheThreadOnItsParentWhenTheDeveloperRepliesUnderTheirOwnMessage() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorEntry parent = question("100.1");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");

        MentorEntry reply = question("100.5");
        MentorConversation thread = conversations.require(workspace.getId(), reply.getConversationId());
        assertThat(thread.getParentEntryId()).isEqualTo(parent.getId());
        assertThat(thread.getParentConversationId()).isEqualTo(main().getId());
        assertThat(thread.getSlackTeamId()).isNull();
    }

    @Test
    void shouldStoreTheReplyInTheThreadOnHephsAnswerWhenTheDeveloperRepliesUnderIt() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorEntry answer = answerPostedAt("100.2");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Why quick?", "100.6", "100.2");

        MentorConversation thread =
                conversations.require(workspace.getId(), question("100.6").getConversationId());
        assertThat(thread.getParentEntryId()).isEqualTo(answer.getId());
    }

    @Test
    void shouldRefuseTheReplyAndStoreNothingWhenItsParentBelongsToAnotherWorkspace() {
        service.handleDm(OTHER_TEAM, CHANNEL, SLACK_USER, "Asked in the other workspace", "500.1", "500.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Asked here", "100.1", "100.1");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Reply under the other one", "500.5", "500.1");

        assertThat(maybeQuestion("500.5")).isEmpty();
        verify(slack)
                .postMessage(
                        eq(workspace.getId()),
                        eq(CHANNEL),
                        eq("500.1"),
                        anyList(),
                        eq(MentorRejectedException.Reason.NOT_A_THREAD_PARENT.userMessage()),
                        eq(SlackMentorPosts.noticeMetadata()));
    }

    @Test
    void shouldRefuseTheReplyWhenTheDmIsNotTheOneTheConversationKnows() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        service.handleDm(TEAM, "D8", SLACK_USER, "From another DM", "100.7", "100.1");

        assertThat(entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D8:100.7"))
                .isEmpty();
    }

    @Test
    void shouldKeepTheNewTextMarkedEditedAndAskNothingNewWhenTheDeveloperEditsADm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practise?", "100.1", "100.1");

        service.handleEdit(TEAM, CHANNEL, SLACK_USER, "100.1", "How is my review practice?");

        MentorEntry edited = question("100.1");
        assertThat(edited.getText()).isEqualTo("How is my review practice?");
        assertThat(edited.getEditedAt()).isNotNull();
        assertThat(turnRepository.countQueuedByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(1);
    }

    @Test
    void shouldEraseTheQuestionAndStopItsTurnWhenTheDeveloperDeletesADm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        service.handleDelete(TEAM, CHANNEL, SLACK_USER, "100.1");

        MentorEntry deleted = question("100.1");
        assertThat(deleted.isRedacted()).isTrue();
        assertThat(deleted.getText()).isEmpty();
        assertThat(turnOf(deleted).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
    }

    @Test
    void shouldStopOnlyTheRunningThreadTurnWhenTheDeveloperStopsItsSlackSession() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorTurn answered = turnOf(question("100.1"));
        answerPostedAt("100.2");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And the tests?", "100.6", "100.1");
        MentorTurn claimed = turns.claimNext(WORKER, Duration.ofMinutes(5)).orElseThrow();
        MentorTurn running = turnOf(question("100.5"));
        MentorTurn followUp = turnOf(question("100.6"));
        assertThat(claimed.getId()).isEqualTo(running.getId());
        assertThat(running.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(followUp.getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(followUp.getConversationId()).isEqualTo(running.getConversationId());
        String stoppedAt =
                SlackTs.ofInstant(Objects.requireNonNull(running.getStartedAt()).plusSeconds(1));
        String stop = """
                {"type":"event_callback","team_id":"%s","event_id":"Ev0SESSIONSTOP","event":{
                  "type":"agent_session_stopped","user":"%s","channel":"%s","thread_ts":"100.1",
                  "event_ts":"%s","streaming_message_ts":[]}}
                """.formatted(TEAM, SLACK_USER, CHANNEL, stoppedAt);
        String dm = """
                {"type":"event_callback","team_id":"%s","event_id":"Ev0DM","event":{
                  "type":"message","channel_type":"im","user":"%s","channel":"%s","ts":"100.7","text":"Hi"}}
                """.formatted(TEAM, SLACK_USER, CHANNEL);
        // The Slack routing itself is wired: the same dispatcher finds the DM handler of this DM.
        assertThat(dispatcher.dispatch(subjectDeriver.subjectFor(JSON.readTree(dm))))
                .isPresent();

        String subject = subjectDeriver.subjectFor(JSON.readTree(stop));
        Optional<IntegrationMessageHandler> handler = dispatcher.dispatch(subject);

        assertThat(handler)
                .as("a handler for the subscribed agent_session_stopped event")
                .isPresent();
        handler.orElseThrow().onMessage(natsMessage(subject, stop));
        assertThat(turnOf(question("100.5")).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turnOf(question("100.6")).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(turnRepository.findById(answered.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.DONE);
    }

    @Test
    void shouldStopTheTurnWhenTheSessionStopNamesTheStreamOfItsReply() {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        delivery.streaming(workspace.getId(), question("100.5").getId(), "300.1");

        deliver(ownThreadStopped(afterStartOf(running), "[\"300.1\"]"));

        assertOnlyTheRunningReplyStopped();
    }

    @Test
    void shouldKeepTheTurnRunningWhenTheSessionStopNamesOnlyOtherStreams() {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        delivery.streaming(workspace.getId(), question("100.5").getId(), "300.1");

        deliver(ownThreadStopped(afterStartOf(running), "[\"300.0\",\"300.2\"]"));

        assertNothingStopped();
    }

    @Test
    void shouldStopTheTurnWhenTheSessionStopIsOneMicrosecondAfterItsStart() {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        Instant startedAt = Objects.requireNonNull(running.getStartedAt());

        deliver(ownThreadStopped(SlackTs.ofInstant(startedAt.plus(1, ChronoUnit.MICROS)), "[]"));

        assertOnlyTheRunningReplyStopped();
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0})
    void shouldKeepTheTurnRunningWhenTheSessionStopIsNotAfterItsStart(long micros) {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        Instant startedAt = Objects.requireNonNull(running.getStartedAt());

        deliver(ownThreadStopped(SlackTs.ofInstant(startedAt.plus(micros, ChronoUnit.MICROS)), "[]"));

        assertNothingStopped();
    }

    @ParameterizedTest
    @CsvSource({
        "T2, U1, D9, 100.1",
        "T9, U1, D9, 100.1",
        "T1, U2, D9, 100.1",
        "T1, U1, D8, 100.1",
        "T1, U1, D9, 100.2",
        "T1, U1, D9, 999.9"
    })
    void shouldKeepTheTurnRunningWhenTheSessionStopIsNotOfItsOwnDmThread(
            String team, String user, String channel, String threadTs) {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        // The other workspace has the same DM and a question at the same ts, but no Hephaestus thread on it.
        service.handleDm(OTHER_TEAM, CHANNEL, SLACK_USER, "Asked in the other workspace", "100.1", "100.1");

        deliver(sessionStopped(team, stopFields(user, channel, threadTs, afterStartOf(running), "[]")));

        assertNothingStopped();
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(otherWorkspace.getId(), "slack:T2:D9:100.1"))
                .hasValueSatisfying(turn -> assertThat(turn.getStatus()).isEqualTo(MentorTurn.Status.QUEUED));
    }

    @Test
    void shouldKeepTheTurnRunningWhenTheSessionStopArrivesAfterSlackWasDisconnected() {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        String eventTs = afterStartOf(running);
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.SLACK)
                .orElseThrow();
        jdbc.update("UPDATE connection SET state = 'UNINSTALLED' WHERE id = ?", connection.getId());

        deliver(ownThreadStopped(eventTs, "[]"));

        assertNothingStopped();
    }

    @Test
    void shouldKeepTheMainTurnRunningWhenTheSessionStopNamesItsTopLevelQuestion() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        assertThat(running.getId()).isEqualTo(question("100.1").getTurnId());

        deliver(ownThreadStopped(afterStartOf(turnOf(question("100.1"))), "[]"));

        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(conversations.findThread(workspace.getId(), question("100.1").getId()))
                .isEmpty();
    }

    @Test
    void shouldKeepAWaitingReplyQueuedWhenNoTurnOfTheThreadRuns() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        answerPostedAt("100.2");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");

        deliver(ownThreadStopped(SlackTs.ofInstant(turnRepository.databaseClock()), "[]"));

        assertThat(statusOf("100.5")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    /**
     * Each event lacks a required string, carries one that is not a string or not a ts, or carries a stream list that
     * is missing, not an array, or holds an entry that is not a non-blank string. The reply's stream is
     * {@code 300.1} and every ts is after its start, so a lax reading would stop the turn.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"streaming_message_ts\":[]",
                "\"user\":\"\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":%3$s,\"event_ts\":\"%4$s\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"later\",\"streaming_message_ts\":[]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\"",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":\"300.1\"",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":{\"ts\":\"300.1\"}",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[300.1]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[\"300.1\",\"\"]",
                "\"user\":\"%1$s\",\"channel\":\"%2$s\",\"thread_ts\":\"%3$s\",\"event_ts\":\"%4$s\",\"streaming_message_ts\":[\"300.1\",null]"
            })
    void shouldIgnoreAMalformedSessionStop(String fields) {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        delivery.streaming(workspace.getId(), question("100.5").getId(), "300.1");

        deliver(sessionStopped(TEAM, fields.formatted(SLACK_USER, CHANNEL, "100.1", afterStartOf(running))));

        assertNothingStopped();
    }

    @Test
    void shouldKeepTheNextReplyRunningWhenSlackDeliversTheStopOfTheEarlierReplyAgain() {
        MentorTurn first = runningThreadTurnWithFollowUp(LEASE);
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And the docs?", "100.8", "100.1");
        Instant clicked = turnRepository.databaseClock();
        assertThat(clicked).isAfter(Objects.requireNonNull(first.getStartedAt()));
        String stopFirst = ownThreadStopped(SlackTs.ofInstant(clicked), "[]");
        deliver(stopFirst);
        assertOnlyTheRunningReplyStopped();
        assertThat(statusOf("100.8")).isEqualTo(MentorTurn.Status.QUEUED);
        turns.end(first.getId(), first.getAttempt(), WORKER, MentorTurn.Status.STOPPED, List.of(), null);
        MentorTurn next = turns.claimNext(WORKER, LEASE).orElseThrow();
        assertThat(next.getId()).isEqualTo(question("100.6").getTurnId());
        assertThat(Objects.requireNonNull(turnOf(question("100.6")).getStartedAt()))
                .isAfter(clicked);

        deliver(stopFirst);

        assertThat(statusOf("100.5")).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(statusOf("100.6")).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(statusOf("100.8")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    @Test
    void shouldCallNoSlackApiWhenASessionStopChangesNothingOrStopsTheTurn() {
        MentorTurn running = runningThreadTurnWithFollowUp(LEASE);
        String atStart = SlackTs.ofInstant(Objects.requireNonNull(running.getStartedAt()));

        service.handleSessionStop(TEAM, CHANNEL, SLACK_USER, "100.1", atStart, List.of());
        assertNothingStopped();
        service.handleSessionStop(TEAM, CHANNEL, SLACK_USER, "100.1", afterStartOf(running), List.of());

        assertOnlyTheRunningReplyStopped();
        verifyNoInteractions(slack);
    }

    /**
     * Attempt 1 of the reply {@code 100.5} streams at {@code 300.1} and loses its lease, and attempt 2 is claimed. The
     * question still names the lost attempt's stream: only the projector's restart of the answer clears it, later.
     *
     * @return a database time during attempt 1, before attempt 2 started
     */
    private Instant retriedWhileTheLostStreamIsRecorded() {
        MentorTurn first = runningThreadTurnWithFollowUp(Duration.ofSeconds(-1));
        delivery.streaming(workspace.getId(), question("100.5").getId(), "300.1");
        Instant duringFirst = turnRepository.databaseClock();
        assertThat(duringFirst).isAfter(Objects.requireNonNull(first.getStartedAt()));
        assertThat(turns.expireLease(first.getId())).isPresent();
        MentorTurn retried = turns.claimNext(WORKER, LEASE).orElseThrow();
        assertThat(retried.getId()).isEqualTo(first.getId());
        assertThat(retried.getAttempt()).isEqualTo(2);
        assertThat(question("100.5").getSlackStreamTs()).isEqualTo("300.1");
        assertThat(duringFirst)
                .isBefore(Objects.requireNonNull(turnOf(question("100.5")).getStartedAt()));
        return duringFirst;
    }

    @Test
    void shouldKeepTheRetriedAttemptRunningWhenTheSessionStopIsOfTheLostAttempt() {
        Instant duringFirst = retriedWhileTheLostStreamIsRecorded();

        deliver(ownThreadStopped(SlackTs.ofInstant(duringFirst), "[\"300.1\"]"));
        deliver(ownThreadStopped(SlackTs.ofInstant(duringFirst), "[]"));

        MentorTurn after = turnOf(question("100.5"));
        assertThat(after.getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(after.getAttempt()).isEqualTo(2);
        assertThat(statusOf("100.6")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"300.1\"]"})
    void shouldStopTheRetriedAttemptWhenTheDeveloperStopsItDuringTheRetry(String streams) {
        retriedWhileTheLostStreamIsRecorded();

        deliver(ownThreadStopped(afterStartOf(turnOf(question("100.5"))), streams));

        MentorTurn after = turnOf(question("100.5"));
        assertThat(after.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(after.getAttempt()).isEqualTo(2);
        assertThat(statusOf("100.6")).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(statusOf("100.1")).isEqualTo(MentorTurn.Status.DONE);
    }

    @Test
    void shouldStoreNothingWhenSlackIsDisconnectedWhileTheDmWaits() throws Exception {
        HeldTransaction disconnecting = HeldTransaction.holdIn(transactionTemplate, jdbc, this::disconnect);
        CompletableFuture<Void> dm = CompletableFuture.runAsync(
                () -> service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1"));

        disconnecting.commitAfter(dm);
        dm.get(20, TimeUnit.SECONDS);

        assertThat(maybeQuestion("100.1")).isEmpty();
        assertThat(conversationRepository
                        .findMain(workspace.getId(), developerId)
                        .map(MentorConversation::getSlackChannelId))
                .isEmpty();
    }

    @Test
    void shouldEraseTheQuestionWhenSlackIsDisconnectedWhileTheDmIsStored() throws Exception {
        HeldTransaction storing = HeldTransaction.holdIn(
                transactionTemplate,
                jdbc,
                () -> service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1"));
        CompletableFuture<Void> disconnecting =
                CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(status -> disconnect()));

        storing.commitAfter(disconnecting);
        disconnecting.get(20, TimeUnit.SECONDS);

        assertThat(question("100.1").isRedacted()).isTrue();
        assertThat(main().getSlackChannelId()).isNull();
    }

    /** What a Slack disconnect does in its transaction: the lifecycle lock, the state, then the erasure. */
    private void disconnect() {
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.SLACK)
                .orElseThrow();
        connectionRepository.acquireLifecycleLock(connection.getId(), workspace.getId());
        jdbc.update("UPDATE connection SET state = 'UNINSTALLED' WHERE id = ?", connection.getId());
        eraser.eraseWorkspace(workspace.getId());
    }

    @Test
    void shouldVoteOnHephsAnswerWhenTheDeveloperPressesAFeedbackButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, SLACK_USER, "NOT_HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isEqualTo(MentorEntry.Vote.NOT_HELPFUL);
    }

    @Test
    void shouldIgnoreTheVoteWhenAnotherSlackMemberPressesTheButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        MentorEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, "U2", "HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(MentorRefusal.class)
    void shouldReplyWithTheReasonAndStoreNothingWhenAdmissionRefusesTheMember(MentorRefusal refusal) {
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(refusal));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        refusal.userMessage(),
                        SlackMentorPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @Test
    void shouldReplyInTheSlackThreadAndStoreNothingWhenAdmissionRefusesAReplyInAThread() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(MentorRefusal.NO_AI));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And then?", "100.5", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        "100.1",
                        List.of(),
                        MentorRefusal.NO_AI.userMessage(),
                        SlackMentorPosts.noticeMetadata());
        assertThat(maybeQuestion("100.5")).isEmpty();
        assertThat(conversations.findThread(workspace.getId(), question("100.1").getId()))
                .isEmpty();
    }

    @Test
    void shouldOfferTheAccountLinkAndStoreNothingWhenTheSlackMemberIsNotLinked() {
        when(identities.resolveActiveAccountId(workspace.getId(), TEAM, SLACK_USER))
                .thenReturn(Optional.empty());

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my PR practice?", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        SlackMentorService.NOT_LINKED,
                        SlackMentorPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldExplainUnavailabilityInTheSameDmOrThreadWhenHephIsNotReady(boolean inThread) {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        String threadTs = inThread ? "100.0" : "100.1";

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", threadTs);

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        inThread ? threadTs : null,
                        List.of(),
                        MentorRefusal.UNAVAILABLE.userMessage(),
                        SlackMentorPosts.noticeMetadata());
        verifyNoMoreInteractions(slack);
        verifyNoInteractions(identities, admission);
        assertNothingStored("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .isEmpty();
    }

    @Test
    void shouldPointToHelpAndStoreNothingWhenTheDmSpeaksOfSelfHarmAndHephIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "I want to kill myself", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        KeywordSlackMentorInputGuard.SELF_HARM_RESPONSE,
                        SlackMentorPosts.noticeMetadata());
        verifyNoMoreInteractions(slack);
        verifyNoInteractions(readiness, identities, admission);
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusiveAndHephIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        verifyNoInteractions(slack, readiness, identities, admission);
        assertNothingStored("100.1");
    }

    @Test
    void shouldPointToHelpAndStoreNothingWhenTheDmSpeaksOfSelfHarm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "I want to kill myself", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        KeywordSlackMentorInputGuard.SELF_HARM_RESPONSE,
                        SlackMentorPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusive() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        verifyNoInteractions(slack);
        assertNothingStored("100.1");
    }
}
