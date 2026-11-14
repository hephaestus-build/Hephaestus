package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.ActiveConnectionGuard;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackNoticeActions;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackMentorDmMessageHandler;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorRejectedException;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackMessages;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackReplies;
import de.tum.cit.aet.hephaestus.mentor.MentorTimeline;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnClaims;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.spi.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** What the developer does in their Slack DM with Hephaestus, and the ledger rows it writes. */
class SlackMentorServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private MentorTurnClaims claims;

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private ActiveConnectionGuard activeConnections;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorEntryService entries;

    @Autowired
    private MentorSlackMessages messages;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTimeline timeline;

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

    @Autowired
    private ProviderEventStore providerEvents;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private EventPayloadDeserializer eventPayloads;

    @Autowired
    private AgentActions actions;

    @Autowired
    private GitProviderRegistry registry;

    @Autowired
    private ObjectMapper mapper;

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
            ConnectionFixtures.reachState(slackConnection, IntegrationState.ACTIVE);
            connectionRepository.save(slackConnection);
            when(identities.resolveActiveAccountId(each.getId(), team, SLACK_USER))
                    .thenReturn(Optional.of(developerId));
            when(readiness.isReady(each.getId())).thenReturn(true);
            when(admission.refusal(each.getId(), developerId)).thenReturn(Optional.empty());
        }
        service = new SlackMentorService(
                new SlackWorkspaceResolver(connectionService, activeConnections),
                new SlackNoticeActions(actions, mapper, registry),
                identities,
                new KeywordSlackMentorInputGuard(),
                readiness,
                admission,
                conversations,
                entries,
                turns,
                timeline);
    }

    private void assertNotice(String messageTs, @Nullable String threadTs, String text, boolean linkAccount) {
        String key = "slack_notice:" + workspace.getId() + ":" + TEAM + ":" + CHANNEL + ":" + messageTs;
        var rows = jdbc.queryForList(
                "SELECT kind,state,payload->>'channelId' AS channel,native_recipient->>'subject' AS recipient,payload->>'threadTs' AS thread,payload->>'text' AS text,payload->>'linkAccount' AS link FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                workspace.getId(),
                key);
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row)
                .containsEntry("kind", "SLACK_NOTICE")
                .containsEntry("state", "ADMITTED")
                .containsEntry("channel", CHANNEL)
                .containsEntry("recipient", SLACK_USER)
                .containsEntry("thread", threadTs)
                .containsEntry("text", text)
                .containsEntry("link", Boolean.toString(linkAccount));
        verifyNoInteractions(slack);
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
        MentorTurn running = claims.claimNext().orElseThrow();
        ObjectNode pi = MentorLedgerFixtures.answer("a1b2c3d4", "Your reviews are quick.");
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(pi), null, null));
        UUID answerId = Objects.requireNonNull(
                turns.answer(workspace.getId(), running.getId()).answerEntryId());
        messages.link(workspace.getId(), answerId, ts);
        return entryRepository.findById(answerId).orElseThrow();
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

    private ProviderEventStore.Claimed claimedDm() {
        byte[] payload = """
                {"team_id":"T1","event":{"type":"message","channel_type":"im","channel":"D9",
                 "user":"U1","text":"How is my review practice?","ts":"100.1"}}
                """.getBytes(StandardCharsets.UTF_8);
        var arrival = new ProviderEventStore.Arrival(
                workspace.getId(),
                "slack",
                "slack.T1.message_im",
                "slack-notice-" + UUID.randomUUID(),
                Map.of(),
                payload);
        assertThat(providerEvents.store(List.of(arrival))).isOne();
        return providerEvents.claim(Duration.ofMinutes(2)).orElseThrow();
    }

    private void dispatchDm(ProviderEventStore.Claimed claimed) {
        var handler = new SlackMentorDmMessageHandler(service, eventPayloads);
        assertThat(inbox.dispatch(
                        claimed.id(),
                        claimed.leaseEpoch(),
                        claimed.event().receivedAt(),
                        () -> handler.onMessage(claimed.event())))
                .isFalse();
    }

    @Test
    void shouldKeepOneAdmissionOutcomeWhenARefusedDmIsRedeliveredAfterReadinessChanges() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        var claimed = claimedDm();
        dispatchDm(claimed);
        assertNotice("100.1", null, MentorRefusal.UNAVAILABLE.userMessage(), false);

        when(readiness.isReady(workspace.getId())).thenReturn(true);
        dispatchDm(claimed);

        assertNothingStored("100.1");
        assertNotice("100.1", null, MentorRefusal.UNAVAILABLE.userMessage(), false);
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
    }

    @Test
    void shouldNotRecreateAnErasedNoticeWhenTheSameInboxEventIsRedelivered() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        var claimed = claimedDm();
        dispatchDm(claimed);
        var recipient = new PersonIdentity(registry.resolveProviderId("SLACK", "https://slack.com"), SLACK_USER, TEAM);
        transactionTemplate.executeWithoutResult(status ->
                assertThat(actions.eraseRecipient(workspace.getId(), recipient)).isEqualTo(1));

        dispatchDm(claimed);

        String key = "slack_notice:" + workspace.getId() + ":T1:D9:100.1";
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        Integer.class,
                        workspace.getId(),
                        key))
                .isZero();
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();
        verifyNoInteractions(slack);
    }

    @Test
    void shouldStoreOneQuestionAndQueueOneCompleteReplyWhenTheInboxReceivesTheSameDmTwice() {
        String delivery = "slack-" + UUID.randomUUID();
        byte[] payload = """
                {"team_id":"T1","event":{"type":"message","channel_type":"im","channel":"D9",
                 "user":"U1","text":"How is my review practice?","ts":"100.1"}}
                """.getBytes(StandardCharsets.UTF_8);
        var arrival = new ProviderEventStore.Arrival(
                workspace.getId(), "slack", "slack.T1.message_im", delivery, Map.of(), payload);
        assertThat(providerEvents.store(List.of(arrival))).isOne();
        assertThat(providerEvents.store(List.of(arrival))).isZero();

        var claimed = providerEvents.claim(Duration.ofMinutes(2)).orElseThrow();
        assertThat(claimed.workspaceId()).isEqualTo(workspace.getId());
        assertThat(claimed.event().payload()).isEqualTo(payload);
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                .isEqualTo("LEASED");
        var handler = new SlackMentorDmMessageHandler(service, eventPayloads);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(inbox.dispatch(
                            claimed.id(),
                            claimed.leaseEpoch(),
                            claimed.event().receivedAt(),
                            () -> handler.onMessage(claimed.event())))
                    .isFalse();
        }
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();

        MentorEntry question = question("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                .isEqualTo("DONE");
        String replyKey = MentorSlackReplies.replyKey(question.getId());
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        Integer.class,
                        workspace.getId(),
                        replyKey))
                .as("Slack receives no partial answer while the turn is queued")
                .isZero();

        answerPostedAt("100.2");

        assertThat(jdbc.queryForList(
                        "SELECT state FROM agent_action WHERE workspace_id=? AND kind='SLACK_POST' AND idempotency_key=?",
                        String.class,
                        workspace.getId(),
                        replyKey))
                .containsExactly("ADMITTED");
        assertThat(jdbc.queryForObject(
                        "SELECT payload->>'entryId' FROM agent_action WHERE workspace_id=? AND idempotency_key=?",
                        String.class,
                        workspace.getId(),
                        replyKey))
                .isEqualTo(question.getId().toString());
        assertThat(providerEvents.store(List.of(arrival)))
                .as("a late redelivery also stays deduplicated")
                .isZero();
        verifyNoInteractions(slack);
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
        assertNotice("500.5", "500.1", MentorRejectedException.Reason.NOT_A_THREAD_PARENT.userMessage(), false);
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
        ConnectionFixtures.reachState(connection, IntegrationState.UNINSTALLED);
        connectionRepository.saveAndFlush(connection);
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

        assertNotice("100.1", null, refusal.userMessage(), false);
        assertNothingStored("100.1");
    }

    @Test
    void shouldReplyInTheSlackThreadAndStoreNothingWhenAdmissionRefusesAReplyInAThread() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(MentorRefusal.NO_AI));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And then?", "100.5", "100.1");

        assertNotice("100.5", "100.1", MentorRefusal.NO_AI.userMessage(), false);
        assertThat(maybeQuestion("100.5")).isEmpty();
        assertThat(conversations.findThread(workspace.getId(), question("100.1").getId()))
                .isEmpty();
    }

    @Test
    void shouldOfferTheAccountLinkAndStoreNothingWhenTheSlackMemberIsNotLinked() {
        when(identities.resolveActiveAccountId(workspace.getId(), TEAM, SLACK_USER))
                .thenReturn(Optional.empty());

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my PR practice?", "100.1", "100.1");

        assertNotice("100.1", null, SlackMentorService.NOT_LINKED, true);
        assertNothingStored("100.1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldExplainUnavailabilityInTheSameDmOrThreadWhenHephIsNotReady(boolean inThread) {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        String threadTs = inThread ? "100.0" : "100.1";

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", threadTs);

        assertNotice("100.1", inThread ? threadTs : null, MentorRefusal.UNAVAILABLE.userMessage(), false);
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

        assertNotice("100.1", null, KeywordSlackMentorInputGuard.SELF_HARM_RESPONSE, false);
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

        assertNotice("100.1", null, KeywordSlackMentorInputGuard.SELF_HARM_RESPONSE, false);
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusive() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        verifyNoInteractions(slack);
        assertNothingStored("100.1");
    }
}
