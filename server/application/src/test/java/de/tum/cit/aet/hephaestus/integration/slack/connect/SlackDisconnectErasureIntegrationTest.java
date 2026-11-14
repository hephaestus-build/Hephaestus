package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport.BoundConversation;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackUninstallService;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewItemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** A Slack disconnect and a Slack app uninstall erase the same data, the conversation-derived practice rows included. */
class SlackDisconnectErasureIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "TERASE";
    private static final String CHANNEL = "CERASE";
    private static final String TS = "1700000000.000100";

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private SlackConnectionStrategy slackStrategy;

    @Autowired
    private SlackUninstallService uninstallService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private ReviewPracticeRepository assignments;

    @Autowired
    private ReviewItemRepository reviewItems;

    @Autowired
    private ItemIndexService items;

    @Autowired
    private ResultRepository results;

    @Autowired
    private FeedbackRepository feedbacks;

    @Autowired
    private FeedbackBasisRepository bases;

    @Autowired
    private SlackMessageRepository messages;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long workspaceId;
    private long memberId;
    private Practice practice;
    private Connection connection;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-disconnect-erase"));
        workspaceId = workspace.getId();
        memberId = SlackConversationTestSupport.accountWithIdentity(
                accounts, identities, provider, 300L, "slack-erase-member");
        memberships.createMembership(workspace, memberId, Membership.WorkspaceRole.ADMIN);
        practice = SlackConversationTestSupport.newPractice(practiceVersions, workspace, "disconnect-erase");
        Connection slack = new Connection(
                workspace, IntegrationKind.SLACK, TEAM, new ConnectionConfig.SlackConfig(TEAM, null, null, Set.of()));
        ConnectionFixtures.reachState(slack, IntegrationState.ACTIVE);
        connection = connectionRepository.saveAndFlush(slack);
    }

    @Test
    void shouldEraseConversationDerivedPracticeRowsWhenAnAdminDisconnectsSlack() {
        BoundConversation conversation = seedConversation();

        connectionService.disconnect(
                connection,
                ConnectionService.TransitionRequest.byAccount(
                        IntegrationState.UNINSTALLED,
                        "DISCONNECT",
                        "ADMIN",
                        memberId,
                        UUID.randomUUID().toString(),
                        null),
                slackStrategy);

        assertErased(conversation);
    }

    @Test
    void shouldEraseTheSameRowsWhenTheSlackAppIsUninstalled() {
        BoundConversation conversation = seedConversation();

        uninstallService.onUninstall(TEAM, "app_uninstalled", "EvErase");

        assertErased(conversation);
        assertThat(connectionRepository.findById(connection.getId()))
                .get()
                .extracting(Connection::getState)
                .isEqualTo(IntegrationState.UNINSTALLED);
    }

    private BoundConversation seedConversation() {
        var seed = new SlackConversationTestSupport(jdbcTemplate);
        seed.seedMessage(workspaceId, TEAM, CHANNEL, TS, TS, "a message in the thread");
        seed.seedThread(workspaceId, CHANNEL, TS, TS, 1, "{" + memberId + "}");
        Long threadId = jdbcTemplate.queryForObject(
                "SELECT id FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ?",
                Long.class,
                workspaceId,
                CHANNEL);
        assertNotNull(threadId);
        BoundConversation conversation = SlackConversationTestSupport.seedBoundConversation(
                reviews,
                assignments,
                reviewItems,
                items,
                results,
                feedbacks,
                bases,
                workspaceId,
                practice,
                threadId,
                memberId);
        assertThat(results.findById(conversation.resultId())).isPresent();
        assertThat(feedbacks.findById(conversation.feedbackId())).isPresent();
        return conversation;
    }

    private void assertErased(BoundConversation conversation) {
        assertThat(results.findById(conversation.resultId())).isEmpty();
        assertThat(feedbacks.findById(conversation.feedbackId())).isEmpty();
        assertThat(messages.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, TS))
                .isFalse();
    }
}
