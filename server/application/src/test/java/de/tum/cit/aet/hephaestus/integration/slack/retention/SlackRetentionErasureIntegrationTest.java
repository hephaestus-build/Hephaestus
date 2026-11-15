package de.tum.cit.aet.hephaestus.integration.slack.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.SlackThreadTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThread;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SlackRetentionErasureIntegrationTest extends BaseIntegrationTest {

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
    private SlackRetentionSweeper slackRetentionSweeper;

    @Autowired
    private SlackThreadRepository slackThreadRepository;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackBasisRepository feedbackEvidenceRepository;

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
    private long recipientAccountId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-retain-erase"));
        practice = savePractice(workspace);
        recipientAccountId = memberships.accountIdForUser(userRepository
                .save(TestUserFactory.createUser(100L, "retain-recipient", provider))
                .getId());
        memberships.createMembership(workspace, recipientAccountId, Membership.WorkspaceRole.MEMBER);
    }

    @Test
    @DisplayName("retention erases the aged thread's derived CONVERSATION rows + drops the aggregate; fresh thread + PR"
            + " survive")
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

        // An unrelated PR result for the same workspace — a different artifact type, MUST survive.
        UUID prObs = seedResult(ScmItemEvents.PULL_REQUEST, 7777L);

        // At least one message so the workspace is enumerated by the sweep; also proves message-grain pruning.
        insertMessage("agedmsg.1", "aged-root", now.minus(Duration.ofDays(60)));
        insertMessage("freshmsg.1", "fresh-root", now);

        slackRetentionSweeper.sweepNow();

        assertThat(slackThreadRepository.findById(agedThreadId)).isEmpty();
        assertThat(practiceResultRepository.findById(agedObs)).isEmpty();
        assertThat(feedbackRepository.findById(agedFb)).isEmpty();

        assertThat(slackThreadRepository.findById(freshThreadId)).isPresent();
        assertThat(practiceResultRepository.findById(freshObs)).isPresent();
        assertThat(feedbackRepository.findById(freshFb)).isPresent();

        assertThat(practiceResultRepository.findById(prObs)).isPresent();

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
        // participant_account_ids is unmapped on the entity, so seed the row natively.
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
                "SELECT participant_account_ids FROM slack_thread WHERE id = ?",
                (rs, n) -> (Long[]) rs.getArray(1).getArray(),
                threadId);
        return List.of(ids);
    }

    private void insertMessage(String slackTs, String slackThreadTs, Instant ingestedAt) {
        jdbcTemplate.update(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, slack_thread_ts,"
                        + " ingested_at) VALUES (?, ?, ?, ?, ?, ?)",
                workspace.getId(),
                "T1",
                "C1",
                slackTs,
                slackThreadTs,
                Timestamp.from(ingestedAt));
    }

    private UUID seedBoundConversation(long threadId) {
        var bound = SlackThreadTestSupport.seedBoundConversation(
                reviews,
                assignments,
                reviewItems,
                items,
                practiceResultRepository,
                feedbackRepository,
                feedbackEvidenceRepository,
                workspace.getId(),
                practice,
                threadId,
                recipientAccountId);
        lastFeedbackId = bound.feedbackId();
        return bound.resultId();
    }

    private UUID seedResult(ItemKind kind, long sourceId) {
        return SlackThreadTestSupport.seedResult(
                reviews,
                assignments,
                reviewItems,
                items,
                practiceResultRepository,
                workspace.getId(),
                practice,
                kind,
                sourceId,
                recipientAccountId);
    }

    private Practice savePractice(Workspace ws) {
        return SlackThreadTestSupport.newPractice(practiceVersions, ws, "retain-practice");
    }
}
