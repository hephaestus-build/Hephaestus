package de.tum.cit.aet.hephaestus.integration.slack.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationTurnCompletion;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestNotes;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestPointer;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.agent.conversation.weeklydigest.WeeklyDigestWritten;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackMessages;
import de.tum.cit.aet.hephaestus.conversation.ConversationSlackReplies;
import de.tum.cit.aet.hephaestus.core.WorkspaceSubdomainProperties;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ModelLinkHosts;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.SlackHephaestusUiLinks;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import de.tum.cit.aet.hephaestus.testconfig.WeeklyDigestTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** The Slack note that a weekly digest is ready: one top-level DM message, sent once, with no contents. */
class SlackWeeklyDigestPointerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TEAM = "T1";
    private static final String SLACK_USER = "U1";
    private static final String DM = "D77";
    private static final WeeklyDigestWindow WEEK =
            new WeeklyDigestWindow(LocalDate.parse("2026-10-05"), ZoneId.of("Europe/Berlin"));

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationSlackMessages messages;

    @Autowired
    private ConversationSlackReplies replies;

    private ActionExecutor executor;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConversationTurnCompletion completion;

    @Autowired
    private ModelLinkHosts linkHosts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WeeklyDigestNotes notes;

    @Autowired
    private WorkspaceSummaryQuery workspaces;

    @Autowired
    private ObjectMapper objectMapper;

    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final ConnectionService connections = mock(ConnectionService.class);
    private final SlackConversationIdentityResolver identities = mock(SlackConversationIdentityResolver.class);
    private final WeeklyDigestSubscriptions subscriptions = mock(WeeklyDigestSubscriptions.class);

    private Workspace workspace;
    private long accountId;
    private Conversation main;
    private SlackWeeklyDigestPointer pointer;

    @BeforeEach
    void setUpPointer() {
        User developer = persistUser("weekly-digest-slack-developer");
        workspace = createWorkspace("digest-slack", "Delivery team", "digest-slack", AccountType.ORG, developer);
        accountId = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), accountId);
        when(slack.isConnected(workspace.getId())).thenReturn(true);
        when(connections.findActive(workspace.getId(), IntegrationKind.SLACK))
                .thenReturn(Optional.of(new Connection(
                        workspace,
                        IntegrationKind.SLACK,
                        TEAM,
                        new ConnectionConfig.SlackConfig(TEAM, "not-a-key", null, Set.of()))));
        when(identities.resolveSlackUserId(accountId, TEAM)).thenReturn(Optional.of(SLACK_USER));
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.of(Instant.parse("2026-01-01T00:00:00Z")));
        var beans = new StaticListableBeanFactory();
        beans.addBean("slack", slack);
        var adapter = new SlackPostAdapter(
                beans.getBeanProvider(SlackMessageService.class),
                beans.getBeanProvider(SlackWeeklyDigestPointer.class),
                replies,
                messages,
                completion,
                linkHosts,
                objectMapper);
        beans.addBean("post", adapter);
        executor = ActionTestSupport.withAdapter(context, adapter);
        pointer = new SlackWeeklyDigestPointer(
                slack,
                connections,
                identities,
                adapter,
                conversations,
                notes,
                subscriptions,
                workspaces,
                new SlackHephaestusUiLinks("https://hephaestus.example", new WorkspaceSubdomainProperties(false, "")),
                replies);
        beans.addBean("pointer", pointer);
    }

    /** How a digest's note stands, as its outbox action shows it. */
    private ConversationEntry.@Nullable SlackDelivery noteDelivery(ConversationEntry digest) {
        return replies.delivery(workspace.getId(), ConversationSlackReplies.digestKey(digest.getId()));
    }

    private void enqueueAndDeliver(WeeklyDigestWritten event) {
        context.getBean(TransactionTemplate.class).executeWithoutResult(status -> pointer.on(event));
        ActionTestSupport.runLane(executor, jdbc, ConversationSlackReplies.digestKey(event.entryId()));
    }

    @Test
    void shouldSendNothingAndLeaveNoActionWhenTheDigestTransactionRollsBack() {
        ConversationEntry digest = digest();
        context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
            pointer.on(written(digest, WeeklyDigestPointer.SLACK));
            verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
            status.setRollbackOnly();
        });
        assertThat(noteDelivery(digest)).isNull();
    }

    /** A note that a server took and did not settle: Slack may show it already. */
    private void taken(ConversationEntry digest) {
        jdbc.update(
                """
                INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                  subject_account_id, max_attempts, next_attempt_at, created_at, updated_at)
                VALUES (uuidv7(), ?, 'SLACK_POST', ?, ?, 'ADMITTED',
                  jsonb_build_object('entryId', ?::text, 'turnId', NULL), ?, 3, now(), now(), now())
                """,
                workspace.getId(),
                ConversationSlackReplies.digestKey(digest.getId()),
                ConversationSlackReplies.digestKey(digest.getId()),
                digest.getId().toString(),
                accountId);
        ActionTestSupport.due(jdbc, ConversationSlackReplies.digestKey(digest.getId()));
        ActionTestSupport.claimLane(jdbc, objectMapper, ConversationSlackReplies.digestKey(digest.getId()))
                .orElseThrow()
                .fence()
                .beginWrite();
        ActionTestSupport.expireLease(jdbc, ConversationSlackReplies.digestKey(digest.getId()));
    }

    /** Runs the note's due attempt on a server that did not write it, as any server's executor does. */
    private void settle(ConversationEntry digest) {
        String key = ConversationSlackReplies.digestKey(digest.getId());
        ActionTestSupport.due(jdbc, key);
        ActionTestSupport.runLane(executor, jdbc, key);
    }

    @Test
    void shouldPostOneNoteWithoutContentsAndLearnTheDmWhenADigestIsWritten() {
        ConversationEntry digest = digest();
        when(slack.postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), anyList(), anyString(), any()))
                .thenReturn(new SlackMessageService.Posted(DM, "300.1"));

        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));
        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LayoutBlock>> blocks = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(slack, times(1))
                .postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), blocks.capture(), text.capture(), any());
        assertThat(text.getValue()).isEqualTo("Your weekly digest for 5–11 October is ready in Hephaestus.");
        String rendered = blocks.getValue().toString();
        assertThat(rendered)
                .contains(
                        "*Your weekly digest is ready*\n5–11 October · Delivery team",
                        SlackWeeklyDigestPointer.OPEN_ACTION,
                        "https://hephaestus.example/w/digest-slack/conversation?entry=" + digest.getId(),
                        SlackWeeklyDigestPointer.TURN_OFF_ACTION,
                        "Reply in this thread to go through it.")
                .doesNotContainPattern("\\d+ (pieces|piece) of feedback");

        ConversationEntry posted = entryRepository.findById(digest.getId()).orElseThrow();
        assertThat(noteDelivery(digest)).isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        assertThat(posted.getSlackTs()).isEqualTo("300.1");
        Conversation bound = conversations.require(workspace.getId(), main.getId());
        assertThat(bound.getSlackChannelId()).isEqualTo(DM);
        assertThat(conversations.findMainBySlack(workspace.getId(), TEAM, SLACK_USER, DM))
                .map(Conversation::getId)
                .contains(main.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"user", "team"})
    void shouldNotBindThePostedDmWhenTheSlackRecipientChangesDuringThePost(String changed) {
        ConversationEntry digest = digest();
        when(slack.postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), anyList(), anyString(), any()))
                .thenAnswer(invocation -> {
                    if (changed.equals("user")) {
                        when(identities.resolveSlackUserId(accountId, TEAM)).thenReturn(Optional.of("U2"));
                    } else {
                        when(connections.findActive(workspace.getId(), IntegrationKind.SLACK))
                                .thenReturn(Optional.of(new Connection(
                                        workspace,
                                        IntegrationKind.SLACK,
                                        "T2",
                                        new ConnectionConfig.SlackConfig("T2", "not-a-key", null, Set.of()))));
                    }
                    return new SlackMessageService.Posted(DM, "300.2");
                });

        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));
        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));

        verify(slack, times(1))
                .postMessage(eq(workspace.getId()), eq(SLACK_USER), isNull(), anyList(), anyString(), any());
        assertThat(noteDelivery(digest)).isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        assertThat(entryRepository.findById(digest.getId()).orElseThrow().getSlackTs())
                .isNull();
        Conversation unbound = conversations.require(workspace.getId(), main.getId());
        assertThat(unbound.getSlackChannelId()).isNull();
        assertThat(unbound.getSlackUserId()).isNull();
        assertThat(jdbc.queryForObject(
                        """
                SELECT readback_ref::jsonb #>> '{recipient,userId}' FROM agent_action
                WHERE workspace_id=? AND idempotency_key=?
                """, String.class, workspace.getId(), ConversationSlackReplies.digestKey(digest.getId())))
                .isEqualTo(SLACK_USER);
    }

    @Test
    void shouldPostNothingWhenTheAccountTurnedTheDigestOffOrOnOnlyAfterItWasWritten() {
        ConversationEntry digest = digest();
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.empty());
        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));

        when(subscriptions.enabledSince(accountId))
                .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        enqueueAndDeliver(written(digest, WeeklyDigestPointer.SLACK));

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(noteDelivery(digest))
                .as("the note was withdrawn before it was posted")
                .isEqualTo(ConversationEntry.SlackDelivery.FAILED);
    }

    @Test
    void shouldPostNothingWhenTheNoteGoesByEmail() {
        enqueueAndDeliver(written(digest(), WeeklyDigestPointer.EMAIL));

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
    }

    @Test
    void shouldFindTheNoteInTheDmInsteadOfPostingItAgainWhenAnotherServerSettlesIt() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, SLACK_USER, DM);
        ConversationEntry digest = digest();
        taken(digest);
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(DM), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of("300.5"), false));
        settle(digest);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        ConversationEntry settled = entryRepository.findById(digest.getId()).orElseThrow();
        assertThat(noteDelivery(digest)).isEqualTo(ConversationEntry.SlackDelivery.DELIVERED);
        assertThat(settled.getSlackTs()).isEqualTo("300.5");
    }

    @Test
    void shouldPostNothingWhenAnotherServerSettlesANoteTheAccountTurnedOffSince() {
        ConversationEntry digest = digest();
        taken(digest);
        when(subscriptions.enabledSince(accountId)).thenReturn(Optional.empty());
        settle(digest);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(entryRepository.findById(digest.getId()).orElseThrow().getSlackTs())
                .isNull();
    }

    @Test
    void shouldNeverPostTheNoteAgainWhenNoServerCanReadAnUnknownDm() {
        ConversationEntry digest = digest();
        taken(digest);

        settle(digest);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        verify(slack, never()).readWorkspaceMessages(anyLong(), anyString(), any(), anyString(), any());
        assertThat(noteDelivery(digest)).isEqualTo(ConversationEntry.SlackDelivery.UNCERTAIN);
    }

    @Test
    void shouldFailTheNoteAndNeverPostItAgainWhenTheDmHistoryDoesNotHoldIt() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, SLACK_USER, DM);
        ConversationEntry digest = digest();
        taken(digest);
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(DM), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), false));

        settle(digest);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(noteDelivery(digest)).isEqualTo(ConversationEntry.SlackDelivery.FAILED);
    }

    @Test
    void shouldLeaveTheNoteUncertainWhenTheDmHistoryHoldsMoreThanThePageWithoutIt() {
        conversations.bindSlackIdentity(workspace.getId(), main.getId(), TEAM, SLACK_USER, DM);
        ConversationEntry digest = digest();
        taken(digest);
        // The developer wrote more messages after the note than one page holds.
        when(slack.readWorkspaceMessages(eq(workspace.getId()), eq(DM), isNull(), anyString(), any()))
                .thenReturn(new SlackMessageService.Matches(List.of(), true));

        settle(digest);

        verify(slack, never()).postMessage(anyLong(), anyString(), any(), anyList(), anyString(), any());
        assertThat(noteDelivery(digest))
                .as("a page without the note does not prove that Slack does not hold it")
                .isEqualTo(ConversationEntry.SlackDelivery.UNCERTAIN);
    }

    @Test
    void shouldReachTheDeveloperOnlyWhenTheyHaveASlackIdentityInTheConnectedTeam() {
        assertThat(pointer.reaches(workspace.getId(), accountId)).isTrue();

        when(identities.resolveSlackUserId(accountId, TEAM)).thenReturn(Optional.empty());

        assertThat(pointer.reaches(workspace.getId(), accountId)).isFalse();
    }

    private ConversationEntry digest() {
        return entries.appendOnce(
                        workspace.getId(),
                        main.getId(),
                        WeeklyDigestTestFixtures.historical(
                                "Weekly digest for 5–11 October 2026.",
                                new WeeklyDigestPayload(WEEK, List.of(), List.of()).toJson(objectMapper),
                                "weekly-digest:" + workspace.getId() + ":" + accountId + ":" + UUID.randomUUID()))
                .entry();
    }

    private WeeklyDigestWritten written(ConversationEntry digest, WeeklyDigestPointer to) {
        return new WeeklyDigestWritten(digest.getId(), workspace.getId(), accountId, WEEK.days(), to, Instant.now());
    }
}
