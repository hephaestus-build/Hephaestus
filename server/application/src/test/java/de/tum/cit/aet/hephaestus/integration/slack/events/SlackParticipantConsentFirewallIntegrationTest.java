package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.DeliveryInbox;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.core.consumer.StoredEventHandling;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
    private PlatformTransactionManager transactionManager;

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
    private ConnectionRepository connectionRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private DeliveryInbox inbox;

    @Autowired
    private ProviderEventStore eventStore;

    private SlackWorkspaceResolver workspaceResolver;
    private SlackRosterIdentityResolver identityResolver;
    private SlackIngestService ingestService;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
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
        channel.setConsentAnnouncedAt(Instant.ofEpochSecond(1));
        monitoredChannelRepository.save(channel);

        // The two resolvers are pure lookups — mocked so the test exercises the REAL gates + REAL persistence.
        workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        identityResolver = mock(SlackRosterIdentityResolver.class);
        long human = Objects.requireNonNull(
                accounts.save(new Account("Allowed human")).getId());
        memberships.createMembership(workspace, human, Membership.WorkspaceRole.MEMBER);
        when(identityResolver.displayName(anyLong(), any(), any())).thenReturn(Optional.of("A person"));
        when(identityResolver.humanAccountId(anyLong(), any(), any(), any())).thenReturn(Optional.of(human));

        ingestService = service(messageRepository);
    }

    private SlackIngestService service(SlackMessageRepository messages) {
        return new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                new SlackChannelConsentGate(monitoredChannelRepository),
                new SlackParticipantConsentGate(participantConsentRepository),
                messages,
                threadRepository,
                identityResolver,
                conversationFeedbackErasure,
                mock(ProviderEventStore.class),
                /* conversationIngestEnabled */ true,
                new TransactionTemplate(transactionManager));
    }

    @Test
    void shouldFinishAnEditAfterItsBaseInsertCommittedAndRecordBothSteps() {
        var faultingMessages = mock(SlackMessageRepository.class, delegatesTo(messageRepository));
        Instant editedAt = Instant.ofEpochSecond(201);
        doThrow(new IllegalStateException("edit transaction failed"))
                .when(faultingMessages)
                .applyEdit(workspaceId, CHANNEL, "200.1", "edited body", editedAt);
        var interrupted = service(faultingMessages);
        var edit = new SlackIngestService.Edit("200.1", null, ALLOWED_USER, "edited body", "201.0");
        long delivery = StoredEventHandling.leased(eventStore, jdbcTemplate, workspaceId, "slack.T1.C1.message");

        assertThatThrownBy(() ->
                        inbox.dispatch(delivery, 1, Instant.now(), () -> interrupted.editMessage(TEAM, CHANNEL, edit)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "200.1"))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT edited_at FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?",
                        Timestamp.class,
                        workspaceId,
                        CHANNEL,
                        "200.1"))
                .isNull();

        inbox.dispatch(delivery, 1, Instant.now(), () -> ingestService.editMessage(TEAM, CHANNEL, edit));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT edited_at FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?",
                        Timestamp.class,
                        workspaceId,
                        CHANNEL,
                        "200.1"))
                .isEqualTo(Timestamp.from(editedAt));
        verify(identityResolver, times(1)).humanAccountId(workspaceId, TEAM, ALLOWED_USER, "A person");
        assertThat(jdbcTemplate.queryForList(
                        "SELECT step_name FROM provider_event_step WHERE event_id = ?", String.class, delivery))
                .containsExactlyInAnyOrder("slack.message.ingest", "slack.message.edit");
    }

    @Test
    @DisplayName("an opted-out author is not stored; a non-opted-out author on the same ACTIVE channel is")
    void personFirewall_dropsOptedOutAuthor_keepsAllowedAuthor() {
        // OPTED_OUT_USER has explicitly opted out of ingestion; ALLOWED_USER has no consent row.
        participantConsentRepository.optOutOfIngestion(workspaceId, OPTED_OUT_USER);

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
        participantConsentRepository.optOutOfIngestion(workspaceId, OPTED_OUT_USER);
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "100.1", null, OPTED_OUT_USER, "while opted out");
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .isFalse();

        // Opt back in (ingestion_opted_out → false), then a fresh message from the same author is now stored.
        participantConsentRepository.optInToIngestion(workspaceId, OPTED_OUT_USER);
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "300.1", null, OPTED_OUT_USER, "after opting back in");

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "300.1"))
                .as("after opting back in, the author's ingest is allowed again")
                .isTrue();
        // The message sent while opted out stays absent (no retroactive ingest).
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .isFalse();
    }

    @Test
    void permanentErasureControl_survivesConsentOptIn_andDoesNotSuppressAnotherTeamOrPerson() {
        Workspace workspace = workspaceRepository.findById(workspaceId).orElseThrow();
        connectionRepository.saveAndFlush(new Connection(
                workspace, IntegrationKind.SLACK, TEAM, new ConnectionConfig.SlackConfig(TEAM, null, null, Set.of())));
        IdentityProvider provider = identityProviderRepository.saveAndFlush(
                new IdentityProvider(IdentityProviderType.SLACK, "https://slack.com"));
        UUID suppressionId = databaseTestUtils.erase(Objects.requireNonNull(provider.getId()), OPTED_OUT_USER, TEAM);
        participantConsentRepository.optInToIngestion(workspaceId, OPTED_OUT_USER);

        ingestService.ingestChannelMessage(TEAM, CHANNEL, "100.1", null, OPTED_OUT_USER, "must stay absent");
        ingestService.ingestChannelMessage(TEAM, CHANNEL, "200.1", null, ALLOWED_USER, "unrelated person");

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "100.1"))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, CHANNEL, "200.1"))
                .isTrue();
        jdbcTemplate.update("UPDATE identity_restriction SET team_key=? WHERE id=?", "T-OTHER", suppressionId);
        assertThat(participantConsentRepository.isPersonProcessingSuppressed(workspaceId, OPTED_OUT_USER))
                .isFalse();
    }
}
