package de.tum.cit.aet.hephaestus.integration.slack.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres proof of the thread-grain Slack retention sweep. Once a thread goes cold (its {@code last_ts} is
 * older than the retention window), the sweep erases the derived {@code chat.conversation_thread} observations/feedback
 * (through the practices erasure port) <b>and</b> drops the {@code slack_thread} aggregate (which holds the
 * {@code participant_member_ids} PII) — while a still-active thread's derived rows and an unrelated PR observation
 * are left intact. The assertions fail if the retention erasure path is removed (the aged derived rows would
 * survive) and fail if it over-reaches (the fresh thread or the PR observation would vanish). Also proves the
 * {@code participant_member_ids} {@code array_remove} prune is workspace-scoped.
 */
class SlackRetentionErasureIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private SlackRetentionSweeper slackRetentionSweeper;

    @Autowired
    private SlackThreadRepository slackThreadRepository;

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
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Workspace workspace;
    private Practice practice;
    private User recipient;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        // Add the raw-JDBC-only participant_member_ids column — see SlackConversationTestSupport.
        jdbcTemplate.execute(
                "ALTER TABLE slack_thread ADD COLUMN IF NOT EXISTS participant_member_ids BIGINT[] NOT NULL DEFAULT '{}'");
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-retain-erase"));
        practice = savePractice(workspace);
        recipient = userRepository.save(TestUserFactory.createUser(100L, "retain-recipient", provider));
        job = newJob(workspace);
    }

    @Test
    @DisplayName(
            "retention erases the aged thread's derived CONVERSATION rows + drops the aggregate; fresh thread + PR survive")
    void retentionErasesAgedThreadDerivedDataAndDropsAggregate() {
        Instant now = Instant.now();
        // No Slack Connection → falls back to the default retention window. The aged thread is well past
        // it; the fresh thread is current.
        long agedThreadId = insertThread("C1", "aged-root", tsOf(now.minus(Duration.ofDays(60))));
        long freshThreadId = insertThread("C1", "fresh-root", tsOf(now));

        UUID agedObs = seedBoundConversation(agedThreadId);
        UUID agedFb = lastFeedbackId;
        UUID freshObs = seedBoundConversation(freshThreadId);
        UUID freshFb = lastFeedbackId;

        // An unrelated PR observation for the same workspace — a different artifact type, MUST survive.
        UUID prObs = seedObservation(ArtifactKinds.PULL_REQUEST, 7777L);

        // At least one message so the workspace is enumerated by the sweep; also proves message-grain pruning.
        insertMessage("agedmsg.1", "aged-root", now.minus(Duration.ofDays(60)));
        insertMessage("freshmsg.1", "fresh-root", now);

        slackRetentionSweeper.sweepNow();

        assertThat(slackThreadRepository.findById(agedThreadId)).isEmpty();
        assertThat(observationRepository.findById(agedObs)).isEmpty();
        assertThat(feedbackRepository.findById(agedFb)).isEmpty();

        assertThat(slackThreadRepository.findById(freshThreadId)).isPresent();
        assertThat(observationRepository.findById(freshObs)).isPresent();
        assertThat(feedbackRepository.findById(freshFb)).isPresent();

        assertThat(observationRepository.findById(prObs)).isPresent();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM slack_message WHERE slack_ts = 'agedmsg.1'", Long.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM slack_message WHERE slack_ts = 'freshmsg.1'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("pruneParticipant array_removes only the target member, only in the target workspace")
    void pruneParticipantIsScoped() {
        Workspace other = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-prune-other"));
        long threadA = insertThreadWithParticipants(workspace.getId(), "CA", "root-a", 100L, 200L);
        long threadB = insertThreadWithParticipants(other.getId(), "CB", "root-b", 100L, 300L);

        int pruned = slackThreadRepository.pruneParticipant(workspace.getId(), 100L);

        assertThat(pruned).isEqualTo(1);
        assertThat(participantIds(threadA)).containsExactly(200L);
        // The other tenant's identical member id is untouched.
        assertThat(participantIds(threadB)).containsExactlyInAnyOrder(100L, 300L);
        // Idempotent: removing a member no thread references is a no-op.
        assertThat(slackThreadRepository.pruneParticipant(workspace.getId(), 999L))
                .isZero();
    }

    private UUID lastFeedbackId = UUID.randomUUID();

    private static String tsOf(Instant instant) {
        return String.format("%010d.000000", instant.getEpochSecond());
    }

    private long insertThread(String channelId, String threadTs, String lastTs) {
        SlackThread thread = new SlackThread();
        thread.setWorkspaceId(workspace.getId());
        thread.setSlackChannelId(channelId);
        thread.setSlackThreadTs(threadTs);
        thread.setFirstTs(lastTs);
        thread.setLastTs(lastTs);
        thread.setMessageCount(1);
        return slackThreadRepository.save(thread).getId();
    }

    private long insertThreadWithParticipants(long workspaceId, String channelId, String threadTs, long... members) {
        // participant_member_ids is unmapped on the entity, so seed the row natively.
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
                channelId,
                threadTs,
                "1700000000.000000",
                "1700000000.000000",
                arr.toString());
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ? AND slack_thread_ts = ?",
                Long.class,
                workspaceId,
                channelId,
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

    private void insertMessage(String slackTs, String slackThreadTs, Instant ingestedAt) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, slack_thread_ts, ingested_at) VALUES (?, ?, ?, ?, ?, ?)",
                workspace.getId(),
                "T1",
                "C1",
                slackTs,
                slackThreadTs,
                Timestamp.from(ingestedAt));
    }

    private UUID seedBoundConversation(long threadId) {
        UUID observationId = seedObservation(ArtifactKinds.CONVERSATION_THREAD, threadId);
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(ArtifactKinds.CONVERSATION_THREAD)
                .artifactId(threadId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CHAT)
                .position((int) (threadId % 1000))
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        lastFeedbackId = feedback.getId();
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
        return observationId;
    }

    private UUID seedObservation(ArtifactKind artifactKind, long artifactId) {
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
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        return observationId;
    }

    private Practice savePractice(Workspace ws) {
        Practice p = new Practice();
        p.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
        p.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        p.setWorkspace(ws);
        p.setSlug("retain-practice-" + ws.getId());
        p.setName("Retention Practice");
        p.setCriteria("Test description");
        p.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        return practiceRepository.save(p);
    }

    private AgentJob newJob(Workspace ws) {
        AgentJob j = new AgentJob();
        j.setWorkspace(ws);
        j.setJobType(AgentJobType.CONVERSATION_REVIEW);
        j.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        return agentJobRepository.save(j);
    }
}
