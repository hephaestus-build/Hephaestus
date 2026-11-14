package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class SlackAppHomeOptOutErasureIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "T1";
    private static final String CHANNEL = "C1";
    private static final String OPTING_OUT_SLACK_USER = "UME";

    @Autowired
    private ReviewPracticeRepository assignments;

    @Autowired
    private ReviewItemRepository reviewItems;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ItemIndexService items;

    @Autowired
    private SlackMessageRepository messageRepository;

    @Autowired
    private SlackThreadRepository threadRepository;

    @Autowired
    private SlackParticipantConsentRepository participantConsentRepository;

    @Autowired
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackBasisRepository feedbackEvidenceRepository;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private MentorConversationService mentorConversations;

    @Autowired
    private MentorConversationRepository mentorConversationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private SlackInteractivityHandler handler;
    private SlackMentorIdentityResolver identityResolver;
    private long workspaceId;
    private long meMemberId;
    private long otherMemberId;
    private Practice practice;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-optout-erase"));
        workspaceId = workspace.getId();
        meMemberId = SlackConversationTestSupport.accountWithIdentity(
                accounts, identities, provider, 100L, "opting-out-user");
        memberships.createMembership(workspace, meMemberId, Membership.WorkspaceRole.MEMBER);
        otherMemberId = SlackConversationTestSupport.accountWithIdentity(
                accounts, identities, provider, 200L, "co-participant");
        memberships.createMembership(workspace, otherMemberId, Membership.WorkspaceRole.MEMBER);
        practice = savePractice(workspace);

        SlackWorkspaceResolver workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        identityResolver = mock(SlackMentorIdentityResolver.class);
        when(identityResolver.resolveAccountId(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(identityResolver.resolveAccountId(workspaceId, TEAM, OPTING_OUT_SLACK_USER))
                .thenReturn(Optional.of(meMemberId));
        when(identityResolver.resolveActiveAccountId(any(Long.class), any(), any()))
                .thenReturn(Optional.empty());

        handler = new SlackInteractivityHandler(
                workspaceResolver,
                identityResolver,
                mock(SlackAppHomeService.class),
                new SlackParticipantConsentService(participantConsentRepository),
                new SlackPersonErasureService(messageRepository, threadRepository, conversationFeedbackErasure),
                mock(SlackMessageService.class),
                mock(SlackMentorService.class),
                mock(WeeklyDigestSubscriptions.class));
    }

    @Test
    @DisplayName("Channel-message opt-out records ingestion consent AND erases the person's data, sparing others + PR")
    void optOut_recordsConsent_andErasesPersonScopedData() {

        insertMessage("me.1", meMemberId, OPTING_OUT_SLACK_USER);
        insertMessage("other.1", otherMemberId, "UOTHER");
        long threadId = insertThreadWithParticipants("root.1", meMemberId, otherMemberId);
        SlackConversationTestSupport.BoundConversation meConv = seedBoundConversation(threadId, meMemberId);
        UUID meObs = meConv.resultId();
        UUID meFb = meConv.feedbackId();
        SlackConversationTestSupport.BoundConversation otherConv = seedBoundConversation(threadId, otherMemberId);
        UUID otherObs = otherConv.resultId();
        UUID otherFb = otherConv.feedbackId();
        UUID prObs = seedResult(ScmItemEvents.PULL_REQUEST, 7777L, meMemberId);
        UUID myDmId = seedOwnMentorDm();

        handler.handleBlockActions(optOut(OPTING_OUT_SLACK_USER));

        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, OPTING_OUT_SLACK_USER))
                .isTrue();

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "me.1"))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "other.1"))
                .isTrue();

        assertThat(participantIds(threadId)).containsExactly(otherMemberId);

        assertThat(practiceResultRepository.findById(meObs)).isEmpty();
        assertThat(feedbackRepository.findById(meFb)).isEmpty();
        assertThat(practiceResultRepository.findById(otherObs)).isPresent();
        assertThat(feedbackRepository.findById(otherFb)).isPresent();

        assertThat(practiceResultRepository.findById(prObs)).isPresent();

        assertThat(mentorConversationRepository.findById(myDmId)).isPresent();
    }

    @Test
    @DisplayName("unlinked Slack user opt-out deletes raw messages by Slack user id")
    void optOut_unlinkedUser_erasesRawMessagesBySlackUserId() {
        String unlinkedSlackUser = "UUNLINKED";
        insertUnlinkedMessage("unlinked.1", unlinkedSlackUser);
        insertMessage("other.1", otherMemberId, "UOTHER");

        handler.handleBlockActions(optOut(unlinkedSlackUser));

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "unlinked.1"))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "other.1"))
                .isTrue();
        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, unlinkedSlackUser))
                .isTrue();
    }

    @Test
    @DisplayName("an opt-out that Slack delivers after a later opt-in keeps the opt-in and erases nothing")
    void shouldKeepTheLaterOptInAndEraseNothingWhenAnEarlierOptOutArrivesLate() {
        insertMessage("me.after-opt-in", meMemberId, OPTING_OUT_SLACK_USER);

        handler.handleBlockActions(
                action(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_IN, OPTING_OUT_SLACK_USER, "1793000010.000100"));
        handler.handleBlockActions(action(
                SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT, OPTING_OUT_SLACK_USER, "1793000000.000100"));

        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, OPTING_OUT_SLACK_USER))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(
                        workspaceId, CHANNEL, "me.after-opt-in"))
                .isTrue();
    }

    private UUID seedOwnMentorDm() {
        var main = mentorConversations.findOrCreateMain(workspaceId, meMemberId);
        return mentorConversations
                .bindSlackIdentity(workspaceId, main.getId(), TEAM, OPTING_OUT_SLACK_USER, "D-MY-DM")
                .getId();
    }

    private ObjectNode optOut(String slackUserId) {
        return action(SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT, slackUserId, null);
    }

    private ObjectNode action(String actionId, String slackUserId, @Nullable String actionTs) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "block_actions");
        payload.putObject("team").put("id", TEAM);
        payload.putObject("user").put("id", slackUserId);
        payload.putObject("channel").put("id", CHANNEL);
        ArrayNode actions = payload.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("action_id", actionId);
        if (actionTs != null) {
            action.put("action_ts", actionTs);
        }
        return payload;
    }

    private void insertMessage(String slackTs, long authorAccountId, String authorSlackUserId) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts,"
                        + " author_slack_user_id, author_account_id, ingested_at) VALUES (?, ?, ?, ?, ?, ?, now())",
                workspaceId,
                TEAM,
                CHANNEL,
                slackTs,
                authorSlackUserId,
                authorAccountId);
    }

    private void insertUnlinkedMessage(String slackTs, String authorSlackUserId) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts,"
                        + " author_slack_user_id, author_account_id, ingested_at) VALUES (?, ?, ?, ?, ?, NULL, now())",
                workspaceId,
                TEAM,
                CHANNEL,
                slackTs,
                authorSlackUserId);
    }

    private long insertThreadWithParticipants(String threadTs, long... members) {
        StringBuilder arr = new StringBuilder("{");
        for (int i = 0; i < members.length; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append(members[i]);
        }
        arr.append('}');
        jdbcTemplate.update(
                "INSERT INTO slack_thread (workspace_id, slack_channel_id, slack_thread_ts, first_ts, last_ts,"
                        + " message_count, participant_account_ids, created_at) VALUES (?, ?, ?, ?, ?, 1, ?::bigint[],"
                        + " now())",
                workspaceId,
                CHANNEL,
                threadTs,
                "1700000000.000000",
                "1700000000.000000",
                arr.toString());
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ? AND slack_thread_ts = ?",
                Long.class,
                workspaceId,
                CHANNEL,
                threadTs);
        assertNotNull(id);
        return id;
    }

    private List<Long> participantIds(long threadId) {
        Long[] ids = jdbcTemplate.queryForObject(
                "SELECT participant_account_ids FROM slack_thread WHERE id = ?",
                (rs, n) -> (Long[]) rs.getArray(1).getArray(),
                threadId);
        return List.of(ids);
    }

    private SlackConversationTestSupport.BoundConversation seedBoundConversation(long threadId, long aboutUserId) {
        return SlackConversationTestSupport.seedBoundConversation(
                reviews,
                assignments,
                reviewItems,
                items,
                practiceResultRepository,
                feedbackRepository,
                feedbackEvidenceRepository,
                workspaceId,
                practice,
                threadId,
                aboutUserId);
    }

    private UUID seedResult(ItemKind artifactKind, long artifactId, long aboutUserId) {
        return SlackConversationTestSupport.seedResult(
                reviews,
                assignments,
                reviewItems,
                items,
                practiceResultRepository,
                workspaceId,
                practice,
                artifactKind,
                artifactId,
                aboutUserId);
    }

    private Practice savePractice(Workspace ws) {
        return SlackConversationTestSupport.newPractice(practiceVersions, ws, "optout-practice");
    }
}
