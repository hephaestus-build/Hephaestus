package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorAdmission;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorReadinessQuery;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorRefusal;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorPosts;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackOnboardingService;
import de.tum.cit.aet.hephaestus.integration.slack.retention.SlackWorkspaceContentEraser;
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
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;

/** What the developer does in their Slack DM with Heph, and the ledger rows it writes. */
class SlackMentorServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TEAM = "T1";
    private static final String OTHER_TEAM = "T2";
    private static final String CHANNEL = "D9";
    private static final String SLACK_USER = "U1";
    private static final String WORKER = "worker-slack-ingress";

    @Autowired
    private MentorConversationService conversations;

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
    private User developer;
    private SlackMentorService service;

    @BeforeEach
    void setUpSlack() {
        developer = persistUser("slack-heph-developer");
        workspace = createWorkspace("slack-heph", "Slack Heph", "slack-heph", AccountType.ORG, developer);
        otherWorkspace = createWorkspace("slack-heph-2", "Slack Heph 2", "slack-heph-2", AccountType.ORG, developer);
        for (Workspace each : List.of(workspace, otherWorkspace)) {
            String team = each.getId().equals(workspace.getId()) ? TEAM : OTHER_TEAM;
            Connection slackConnection = new Connection(
                    each, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, "Slack", null, Set.of()));
            slackConnection.setState(IntegrationState.ACTIVE);
            connectionRepository.save(slackConnection);
            when(identities.resolveActiveMember(each.getId(), team, SLACK_USER))
                    .thenReturn(Optional.of(
                            new SlackMentorIdentityResolver.Member(developer.getId(), List.of(developer.getId()))));
            when(readiness.isReady(each.getId())).thenReturn(true);
            when(admission.refusal(each.getId(), developer.getId())).thenReturn(Optional.empty());
        }
        service = new SlackMentorService(
                new SlackWorkspaceResolver(connectionRepository, transactionTemplate),
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
        return conversationRepository
                .findMainsByWorkspaceIdAndUserIdIn(workspace.getId(), Set.of(developer.getId()))
                .stream()
                .findFirst()
                .orElseThrow();
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
        assertThat(
                        conversationRepository
                                .findMainsByWorkspaceIdAndUserIdIn(workspace.getId(), Set.of(developer.getId()))
                                .stream()
                                .findFirst())
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
    void shouldStoreNothingWhenSlackIsDisconnectedWhileTheDmWaits() throws Exception {
        HeldTransaction disconnecting = HeldTransaction.holdIn(transactionTemplate, jdbc, this::disconnect);
        CompletableFuture<Void> dm = CompletableFuture.runAsync(
                () -> service.handleDm(TEAM, CHANNEL, SLACK_USER, "How is my review practice?", "100.1", "100.1"));

        disconnecting.commitAfter(dm);
        dm.get(20, TimeUnit.SECONDS);

        assertThat(maybeQuestion("100.1")).isEmpty();
        assertThat(conversationRepository.findMainsByWorkspaceIdAndUserIdIn(
                        workspace.getId(), Set.of(developer.getId())))
                .allSatisfy(main -> assertThat(main.getSlackChannelId()).isNull());
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
        when(admission.refusal(workspace.getId(), developer.getId())).thenReturn(Optional.of(refusal));

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
        when(admission.refusal(workspace.getId(), developer.getId())).thenReturn(Optional.of(MentorRefusal.NO_AI));

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
        when(identities.resolveActiveMember(workspace.getId(), TEAM, SLACK_USER))
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

    @Test
    void shouldIgnoreTheDmAndStoreNothingWhenHephIsNotReady() {
        when(readiness.isReady(workspace.getId())).thenReturn(false);

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Can you review my work?", "100.1", "100.1");

        verifyNoInteractions(slack);
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

    @Test
    void shouldKeepTheAccountsOneConversationWhenTheWorkspaceReadsTheAccountAsAnotherActor() {
        User gitlab = persistUser("slack-heph-gitlab-actor");
        MentorConversation earlier =
                conversations.findOrCreateMain(workspace.getId(), gitlab.getId(), Set.of(gitlab.getId()));
        conversations.bindSlackIdentity(workspace.getId(), earlier.getId(), TEAM, SLACK_USER, CHANNEL);
        turns.submit(
                workspace.getId(),
                earlier.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.SLACK, "Asked as the GitLab actor", "slack:T1:D9:90.1", "90.1", null));
        when(identities.resolveActiveMember(workspace.getId(), TEAM, SLACK_USER))
                .thenReturn(Optional.of(new SlackMentorIdentityResolver.Member(
                        developer.getId(), List.of(gitlab.getId(), developer.getId()))));

        service.handleDm(TEAM, CHANNEL, SLACK_USER, "Asked as the GitHub actor", "100.1", "100.1");
        service.handleDm(TEAM, CHANNEL, SLACK_USER, "A reply under the earlier message", "90.5", "90.1");

        assertThat(conversationRepository
                        .findMainsByWorkspaceIdAndUserIdIn(workspace.getId(), Set.of(gitlab.getId(), developer.getId()))
                        .stream()
                        .findFirst())
                .hasValueSatisfying(main -> {
                    assertThat(main.getId()).isEqualTo(earlier.getId());
                    assertThat(main.getUserId()).isEqualTo(developer.getId());
                });
        assertThat(question("100.1").getConversationId()).isEqualTo(earlier.getId());
        MentorConversation thread =
                conversations.require(workspace.getId(), question("90.5").getConversationId());
        assertThat(thread.getParentConversationId()).isEqualTo(earlier.getId());
    }
}
