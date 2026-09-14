package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-Postgres proof of the person-level ingestion firewall (the #1-defect fix). With the capability on and the
 * channel {@code ACTIVE}, an author who opted OUT of ingestion has their message dropped, while a non-opted-out
 * author on the SAME channel is stored — the composed gate is
 * {@code capability AND channel==ACTIVE AND NOT ingestionOptedOut(workspace, author)}. Then opting the person back
 * in re-allows their ingest. The assertions fail if the person firewall is removed (the opted-out author's message
 * would be stored).
 */
class SlackParticipantConsentFirewallIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "T1";
    private static final String CHANNEL = "C1";
    private static final String OPTED_OUT_USER = "UOUT";
    private static final String ALLOWED_USER = "UOK";

    @Autowired
    private SlackMessageRepository messageRepository;

    @Autowired
    private SlackThreadRepository threadRepository;

    @Autowired
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private SlackParticipantConsentRepository participantConsentRepository;

    @Autowired
    private ConversationFeedbackErasure conversationFeedbackErasure;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SlackWorkspaceResolver workspaceResolver;
    private SlackMentorIdentityResolver identityResolver;
    private SlackIngestService ingestService;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // Add the raw-JDBC-only participant_member_ids column — see SlackConversationTestSupport.
        jdbcTemplate.execute(
                "ALTER TABLE slack_thread ADD COLUMN IF NOT EXISTS participant_member_ids BIGINT[] NOT NULL DEFAULT '{}'");
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-person-firewall"));
        workspaceId = workspace.getId();

        // Channel is ACTIVE (the admin/channel gate is open) so only the person firewall can differ the two authors.
        SlackMonitoredChannel channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setSlackTeamId(TEAM);
        channel.setSlackChannelId(CHANNEL);
        channel.setConsentState(ConsentState.ACTIVE);
        // Announced well before every test message ts (100.1 / 200.1 / 300.1), so the forward-only ingest invariant
        // (ts > consent_announced_at) is satisfied and only the person firewall differentiates the two authors.
        channel.setConsentAnnouncedAt(java.time.Instant.ofEpochSecond(1));
        monitoredChannelRepository.save(channel);

        // The two resolvers are pure lookups — mocked so the test exercises the REAL gates + REAL persistence.
        workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        identityResolver = mock(SlackMentorIdentityResolver.class);
        // Authors are unlinked members for this test (author_member_id stamps null) — identity is irrelevant to the
        // firewall, which keys on the Slack user id.
        when(identityResolver.resolveMemberId(anyLong(), any(), any())).thenReturn(Optional.empty());

        ingestService = new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                new SlackChannelConsentGate(monitoredChannelRepository),
                new SlackParticipantConsentGate(participantConsentRepository),
                messageRepository,
                threadRepository,
                identityResolver,
                conversationFeedbackErasure,
                /* conversationIngestEnabled */ true);
    }

    @Test
    @DisplayName("an opted-out author is not stored; a non-opted-out author on the same ACTIVE channel is")
    void personFirewall_dropsOptedOutAuthor_keepsAllowedAuthor() {
        // OPTED_OUT_USER has explicitly opted out of ingestion; ALLOWED_USER has no consent row.
        participantConsentRepository.optOutOfIngestion(workspaceId, OPTED_OUT_USER, "SLACK_APP_HOME");

        ingestService.ingestChannelMessage(TEAM, CHANNEL, "100.1", null, OPTED_OUT_USER, "opted-out says hi");
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "200.1", null, ALLOWED_USER, "allowed says hi");

        // The opted-out author's message is NOT persisted; the allowed author's IS.
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .as("opted-out author's message must be dropped by the person firewall")
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "200.1"))
                .as("non-opted-out author's message must be stored")
                .isTrue();
    }

    @Test
    @DisplayName("opting back in re-allows a previously opted-out author's ingest")
    void personFirewall_optBackIn_reallowsIngest() {
        participantConsentRepository.optOutOfIngestion(workspaceId, OPTED_OUT_USER, "SLACK_APP_HOME");
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "100.1", null, OPTED_OUT_USER, "while opted out");
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .isFalse();

        // Opt back in (ingestion_opted_out → false), then a fresh message from the same author is now stored.
        participantConsentRepository.optInToIngestion(workspaceId, OPTED_OUT_USER, "SLACK_APP_HOME");
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "300.1", null, OPTED_OUT_USER, "after opting back in");

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "300.1"))
                .as("after opting back in, the author's ingest is allowed again")
                .isTrue();
        // The message sent while opted out stays absent (no retroactive ingest).
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .isFalse();
    }

    @Test
    @DisplayName("channel-message opt-out preserves the historical research column")
    void channelMessageOptOut_preservesHistoricalResearchValue() {
        participantConsentRepository.optInToIngestion(workspaceId, OPTED_OUT_USER, "SLACK_APP_HOME");
        jdbcTemplate.update(
                "UPDATE slack_participant_consent SET research_opted_out = true WHERE workspace_id = ? AND slack_user_id = ?",
                workspaceId,
                OPTED_OUT_USER);

        participantConsentRepository.optOutOfIngestion(
                workspaceId, OPTED_OUT_USER, SlackParticipantConsentService.SOURCE_SLACK_CHANNEL_NOTICE);

        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, OPTED_OUT_USER))
                .isTrue();
        Boolean researchOptedOut = jdbcTemplate.queryForObject(
                "SELECT research_opted_out FROM slack_participant_consent WHERE workspace_id = ? AND slack_user_id = ?",
                Boolean.class,
                workspaceId,
                OPTED_OUT_USER);
        assertThat(researchOptedOut).isTrue();
    }
}
