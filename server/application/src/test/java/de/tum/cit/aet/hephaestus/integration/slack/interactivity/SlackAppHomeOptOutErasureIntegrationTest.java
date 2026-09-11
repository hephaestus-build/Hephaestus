package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationCommand;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.mentor.ThreadSurface;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real-Postgres proof that a Slack channel-message opt-out both STOPS future ingestion (records the person consent) and ERASES
 * that person's already-stored Slack data — driven end-to-end through {@link SlackInteractivityHandler} with the REAL
 * {@link SlackParticipantConsentService} + {@link SlackPersonErasureService} (only the pure resolvers/effects are
 * mocked). It asserts the erasure is person + tenant scoped: a co-participant's message, their id in the
 * {@code participant_member_ids} array, and their CONVERSATION feedback survive, and an unrelated PR observation
 * survives. The assertions fail if erasure over-reaches (the co-participant/PR rows would vanish).
 */
class SlackAppHomeOptOutErasureIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "T1";
    private static final String CHANNEL = "C1";
    private static final String OPTING_OUT_SLACK_USER = "UME";

    @Autowired
    private SlackMessageRepository messageRepository;

    @Autowired
    private SlackThreadRepository threadRepository;

    @Autowired
    private SlackParticipantConsentRepository participantConsentRepository;

    @Autowired
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatThreadRepository chatThreadRepository;

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
    private AgentJob job;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        jdbcTemplate.execute(
                "ALTER TABLE slack_thread ADD COLUMN IF NOT EXISTS participant_member_ids BIGINT[] NOT NULL DEFAULT '{}'");
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-optout-erase"));
        workspaceId = workspace.getId();
        meMemberId = userRepository
                .save(TestUserFactory.createUser(100L, "opting-out-user", provider))
                .getId();
        otherMemberId = userRepository
                .save(TestUserFactory.createUser(200L, "co-participant", provider))
                .getId();
        practice = savePractice(workspace);
        job = newJob(workspace);

        // Handler under test: REAL consent + erasure services; the resolvers/effects are pure and mocked.
        SlackWorkspaceResolver workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        identityResolver = mock(SlackMentorIdentityResolver.class);
        when(identityResolver.resolveMemberId(anyLong(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(identityResolver.resolveMemberId(workspaceId, TEAM, OPTING_OUT_SLACK_USER))
                .thenReturn(Optional.of(meMemberId));
        when(identityResolver.resolveDeveloperLogin(any(Long.class), any(), any()))
                .thenReturn(Optional.empty());

        handler = new SlackInteractivityHandler(
                workspaceResolver,
                identityResolver,
                mock(ResearchParticipationCommand.class),
                mock(SlackAppHomeService.class),
                new SlackParticipantConsentService(participantConsentRepository),
                new SlackPersonErasureService(messageRepository, threadRepository, conversationFeedbackErasure),
                mock(SlackMessageService.class));
    }

    @Test
    @DisplayName("Channel-message opt-out records ingestion consent AND erases the person's data, sparing others + PR")
    void optOut_recordsConsent_andErasesPersonScopedData() {
        // Stored content: a message from the opting-out member and one from a co-participant, a thread they both
        // joined, CONVERSATION feedback about each of them, an unrelated PR observation about the opting-out user,
        // and the opting-out person's own mentor DM thread (not channel ingestion — must survive).
        insertMessage("me.1", meMemberId, OPTING_OUT_SLACK_USER);
        insertMessage("other.1", otherMemberId, "UOTHER");
        long threadId = insertThreadWithParticipants("root.1", meMemberId, otherMemberId);
        SlackConversationTestSupport.BoundConversation meConv = seedBoundConversation(threadId, meMemberId);
        UUID meObs = meConv.observationId();
        UUID meFb = meConv.feedbackId();
        SlackConversationTestSupport.BoundConversation otherConv = seedBoundConversation(threadId, otherMemberId);
        UUID otherObs = otherConv.observationId();
        UUID otherFb = otherConv.feedbackId();
        UUID prObs = seedObservation(ArtifactKinds.PULL_REQUEST, 7777L, meMemberId);
        UUID myDmId = seedOwnMentorDm();

        handler.handleBlockActions(optOut(OPTING_OUT_SLACK_USER));

        // 1) Future ingestion is now blocked for this person (consent recorded, ingestion_opted_out = true).
        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, OPTING_OUT_SLACK_USER))
                .isTrue();

        // 2) The person's stored message is erased; the co-participant's remains.
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "me.1"))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "other.1"))
                .isTrue();

        // 3) The person's id is pruned out of participant_member_ids; the co-participant stays.
        assertThat(participantIds(threadId)).containsExactly(otherMemberId);

        // 4) The CONVERSATION feedback/observation ABOUT the person is erased; the co-participant's survives.
        assertThat(observationRepository.findById(meObs)).isEmpty();
        assertThat(feedbackRepository.findById(meFb)).isEmpty();
        assertThat(observationRepository.findById(otherObs)).isPresent();
        assertThat(feedbackRepository.findById(otherFb)).isPresent();

        // 5) The unrelated PR observation (a different artifact type) is untouched — no over-reach.
        assertThat(observationRepository.findById(prObs)).isPresent();

        // 6) The person's own mentor DM thread is not channel ingestion — left intact.
        assertThat(chatThreadRepository.findById(myDmId)).isPresent();
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

    // --- payload + fixtures ---

    /** The opting-out person's own mentor DM thread — not channel ingestion, must survive a channel opt-out. */
    private UUID seedOwnMentorDm() {
        User me = userRepository.findById(meMemberId).orElseThrow();
        Workspace ws = workspaceRepository.findById(workspaceId).orElseThrow();
        ChatThread myDm = new ChatThread();
        myDm.setId(UUID.randomUUID());
        myDm.setTitle("My Slack DM");
        myDm.setUser(me);
        myDm.setWorkspace(ws);
        myDm.setSurface(ThreadSurface.SLACK_DM);
        return chatThreadRepository.save(myDm).getId();
    }

    private ObjectNode optOut(String slackUserId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "block_actions");
        payload.putObject("team").put("id", TEAM);
        payload.putObject("user").put("id", slackUserId);
        payload.putObject("channel").put("id", CHANNEL);
        ArrayNode actions = payload.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("action_id", SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        action.put("value", "false");
        return payload;
    }

    private void insertMessage(String slackTs, long authorMemberId, String authorSlackUserId) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, author_slack_user_id, author_member_id, ingested_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now())",
                workspaceId,
                TEAM,
                CHANNEL,
                slackTs,
                authorSlackUserId,
                authorMemberId);
    }

    private void insertUnlinkedMessage(String slackTs, String authorSlackUserId) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, author_slack_user_id, author_member_id, ingested_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, now())",
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
                "INSERT INTO slack_thread (workspace_id, slack_channel_id, slack_thread_ts, first_ts, last_ts, message_count, participant_member_ids, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, 1, ?::bigint[], now())",
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
                "SELECT participant_member_ids FROM slack_thread WHERE id = ?",
                (rs, n) -> (Long[]) rs.getArray(1).getArray(),
                threadId);
        return List.of(ids);
    }

    private SlackConversationTestSupport.BoundConversation seedBoundConversation(long threadId, long aboutUserId) {
        return SlackConversationTestSupport.seedBoundConversation(
                observationRepository,
                feedbackRepository,
                feedbackObservationRepository,
                workspaceId,
                job.getId(),
                practice.getId(),
                threadId,
                aboutUserId);
    }

    private UUID seedObservation(ArtifactKind artifactKind, long artifactId, long aboutUserId) {
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "occ-" + observationId,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                artifactKind.value(),
                artifactId,
                aboutUserId,
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationId;
    }

    private Practice savePractice(Workspace ws) {
        return SlackConversationTestSupport.newPractice(practiceRepository, ws, "optout-practice");
    }

    private AgentJob newJob(Workspace ws) {
        return SlackConversationTestSupport.newConversationReviewJob(agentJobRepository, ws);
    }
}
