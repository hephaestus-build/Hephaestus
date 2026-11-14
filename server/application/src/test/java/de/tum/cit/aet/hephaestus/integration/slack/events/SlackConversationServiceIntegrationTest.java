package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationAdmission;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationRefusal;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationRejectedException;
import de.tum.cit.aet.hephaestus.conversation.ConversationRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.conversation.ConversationTimeline;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnClaims;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
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
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationPosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.integration.slack.webhook.SlackConversationDmMessageHandler;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationReadinessQuery;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** What the developer does in their Slack DM with Hephaestus, and the ledger rows it writes. */
class SlackConversationServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ConversationTurnClaims claims;

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ActiveConnectionGuard activeConnections;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationSlackMessages messages;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTimeline timeline;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationTurnRepository turnRepository;

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

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final SlackConversationIdentityResolver identities = mock(SlackConversationIdentityResolver.class);
    private final ConversationReadinessQuery readiness = mock(ConversationReadinessQuery.class);
    private final ConversationAdmission admission = mock(ConversationAdmission.class);

    private Workspace workspace;
    private Workspace otherWorkspace;
    private long developerId;
    private SlackConversationService service;

    @BeforeEach
    void setUpSlack() {
        User developer = persistUser("slack-ask-developer");
        developerId = accountId(developer);
        workspace = createWorkspace("slack-ask", "Slack Hephaestus", "slack-ask", AccountType.ORG, developer);
        otherWorkspace =
                createWorkspace("slack-ask-2", "Slack Hephaestus 2", "slack-ask-2", AccountType.ORG, developer);
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
        service = new SlackConversationService(
                new SlackWorkspaceResolver(connectionService, activeConnections),
                slack,
                identities,
                new KeywordSlackConversationInputGuard(),
                mock(SlackOnboardingService.class),
                readiness,
                admission,
                conversations,
                entries,
                turns,
                timeline);
    }

    private Conversation main() {
        return conversationRepository.findMain(workspace.getId(), developerId).orElseThrow();
    }

    private ConversationEntry question(String ts) {
        return entryRepository
                .findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts)
                .orElseThrow();
    }

    private Optional<ConversationEntry> maybeQuestion(String ts) {
        return entryRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:" + ts);
    }

    /** Nothing of the DM reached the ledger: no conversation, no question. */
    private void assertNothingStored(String ts) {
        assertThat(conversationRepository.findMain(workspace.getId(), developerId))
                .isEmpty();
        assertThat(maybeQuestion(ts)).isEmpty();
    }

    private ConversationTurn turnOf(ConversationEntry question) {
        return turnRepository
                .findById(Objects.requireNonNull(question.getTurnId()))
                .orElseThrow();
    }

    /** Runs the main conversation's turn to an answer that Slack shows at {@code ts}. */
    private ConversationEntry answerPostedAt(String ts) {
        ConversationTurn running = claims.claimNext().orElseThrow();
        ObjectNode pi = ConversationLedgerFixtures.answer("a1b2c3d4", "Your reviews are quick.");
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(pi), null, null));
        UUID answerId = Objects.requireNonNull(
                turns.answer(workspace.getId(), running.getId()).answerEntryId());
        messages.link(workspace.getId(), answerId, ts);
        return entryRepository.findById(answerId).orElseThrow();
    }

    @Test
    void shouldStoreAQuestionInTheMainConversationWhenTheDeveloperWritesATopLevelDm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        Conversation main = main();
        assertThat(main.getSlackTeamId()).isEqualTo(TEAM);
        assertThat(main.getSlackUserId()).isEqualTo(SLACK_USER);
        assertThat(main.getSlackChannelId()).isEqualTo(CHANNEL);
        ConversationEntry question = question("100.1");
        assertThat(question.getConversationId()).isEqualTo(main.getId());
        assertThat(question.getKind()).isEqualTo(ConversationEntry.Kind.USER);
        assertThat(question.getSurface()).isEqualTo(ConversationEntry.Surface.SLACK);
        assertThat(question.getSlackTs()).isEqualTo("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        ConversationTurn turn = turnOf(question);
        assertThat(turn.getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(turn.getSurface()).isEqualTo(ConversationTurn.Surface.SLACK);
    }

    @Test
    void shouldStoreOneQuestionWhenSlackDeliversTheSameDmTwice() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        ConversationEntry question = question("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .hasValueSatisfying(turn -> assertThat(turn.getId()).isEqualTo(question.getTurnId()));
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
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
        var handler = new SlackConversationDmMessageHandler(service, eventPayloads);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(inbox.dispatch(
                            claimed.id(),
                            claimed.leaseEpoch(),
                            claimed.event().receivedAt(),
                            () -> handler.onMessage(claimed.event())))
                    .isFalse();
        }
        assertThat(providerEvents.complete(claimed.id(), claimed.leaseEpoch())).isTrue();

        ConversationEntry question = question("100.1");
        assertThat(question.getText()).isEqualTo("How is my review practice?");
        assertThat(entryRepository.findMaxSeqByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(question.getSeq());
        assertThat(jdbc.queryForObject("SELECT state FROM provider_event WHERE id=?", String.class, claimed.id()))
                .isEqualTo("DONE");
        String replyKey = ConversationSlackReplies.replyKey(question.getId());
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
        ConversationEntry parent = question("100.1");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Tell me more", "100.5", "100.1");

        ConversationEntry reply = question("100.5");
        Conversation thread = conversations.require(workspace.getId(), reply.getConversationId());
        assertThat(thread.getParentEntryId()).isEqualTo(parent.getId());
        assertThat(thread.getParentConversationId()).isEqualTo(main().getId());
        assertThat(thread.getSlackTeamId()).isNull();
    }

    @Test
    void shouldStoreTheReplyInTheThreadOnHephaestusAnswerWhenTheDeveloperRepliesUnderIt() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Why quick?", "100.6", "100.2");

        Conversation thread =
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
                        eq(ConversationRejectedException.Reason.NOT_A_THREAD_PARENT.userMessage()),
                        eq(SlackConversationPosts.noticeMetadata()));
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

        ConversationEntry edited = question("100.1");
        assertThat(edited.getText()).isEqualTo("How is my review practice?");
        assertThat(edited.getEditedAt()).isNotNull();
        assertThat(turnRepository.countQueuedByWorkspaceIdAndConversationId(workspace.getId(), main().getId()))
                .isEqualTo(1);
    }

    @Test
    void shouldEraseTheQuestionAndStopItsTurnWhenTheDeveloperDeletesADm() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");

        service.handleDelete(TEAM, CHANNEL, SLACK_USER, "100.1");

        ConversationEntry deleted = question("100.1");
        assertThat(deleted.isRedacted()).isTrue();
        assertThat(deleted.getText()).isEmpty();
        assertThat(turnOf(deleted).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
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
                        .map(Conversation::getSlackChannelId))
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
    void shouldVoteOnHephaestusAnswerWhenTheDeveloperPressesAFeedbackButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, SLACK_USER, "NOT_HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isEqualTo(ConversationEntry.Vote.NOT_HELPFUL);
    }

    @Test
    void shouldIgnoreTheVoteWhenAnotherSlackMemberPressesTheButton() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        ConversationEntry answer = answerPostedAt("100.2");

        service.handleFeedback(TEAM, CHANNEL, "U2", "HELPFUL:" + answer.getId());

        assertThat(entryRepository.findById(answer.getId()).orElseThrow().getVote())
                .isNull();
    }

    @ParameterizedTest
    @EnumSource(ConversationRefusal.class)
    void shouldReplyWithTheReasonAndStoreNothingWhenAdmissionRefusesTheMember(ConversationRefusal refusal) {
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(refusal));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        refusal.userMessage(),
                        SlackConversationPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @Test
    void shouldReplyInTheSlackThreadAndStoreNothingWhenAdmissionRefusesAReplyInAThread() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1");
        when(admission.refusal(workspace.getId(), developerId)).thenReturn(Optional.of(ConversationRefusal.NO_AI));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "And then?", "100.5", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        "100.1",
                        List.of(),
                        ConversationRefusal.NO_AI.userMessage(),
                        SlackConversationPosts.noticeMetadata());
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
                        SlackConversationService.NOT_LINKED,
                        SlackConversationPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldExplainUnavailabilityInTheSameDmOrThreadWhenHephaestusIsNotReady(boolean inThread) {
        when(readiness.isReady(workspace.getId())).thenReturn(false);
        String threadTs = inThread ? "100.0" : "100.1";

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", threadTs);

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        inThread ? threadTs : null,
                        List.of(),
                        ConversationRefusal.UNAVAILABLE.userMessage(),
                        SlackConversationPosts.noticeMetadata());
        verifyNoMoreInteractions(slack);
        verifyNoInteractions(identities, admission);
        assertNothingStored("100.1");
        assertThat(turnRepository.findByWorkspaceIdAndRequestId(workspace.getId(), "slack:T1:D9:100.1"))
                .isEmpty();
    }

    @Test
    void shouldPointToHelpAndStoreNothingWhenTheDmSpeaksOfSelfHarmAndHephaestusIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "I want to kill myself", "100.1", "100.1");

        verify(slack)
                .postMessage(
                        workspace.getId(),
                        CHANNEL,
                        null,
                        List.of(),
                        KeywordSlackConversationInputGuard.SELF_HARM_RESPONSE,
                        SlackConversationPosts.noticeMetadata());
        verifyNoMoreInteractions(slack);
        verifyNoInteractions(readiness, identities, admission);
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusiveAndHephaestusIsNotReady() {
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
                        KeywordSlackConversationInputGuard.SELF_HARM_RESPONSE,
                        SlackConversationPosts.noticeMetadata());
        assertNothingStored("100.1");
    }

    @Test
    void shouldStayQuietAndStoreNothingWhenTheDmIsAbusive() {
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "stupid bot", "100.1", "100.1");

        verifyNoInteractions(slack);
        assertNothingStored("100.1");
    }
}
