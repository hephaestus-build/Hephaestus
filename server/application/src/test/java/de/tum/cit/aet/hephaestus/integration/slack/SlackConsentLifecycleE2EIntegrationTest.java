package de.tum.cit.aet.hephaestus.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.ReviewJobService;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.WeeklyDigestSubscriptions;
import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionTestSupport;
import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelActivation;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelJoinActions;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackConsentActions;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackMonitoredChannelDTO;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEvent;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackMentorService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.identity.SlackRosterIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.interactivity.SlackInteractivityHandler;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackConsentTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackInteractiveReplyAdapter;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Service-layer lifecycle test; HTTP authorization is covered by SlackChannelAdminControllerIntegrationTest.
 * SlackConversationSchemaContractIntegrationTest covers the Liquibase schema.
 *
 * Ingest requires timestamps after consent, but review requires a settled thread.
 * The review fixture therefore uses aged timestamps independently of the live-ingest fixture. */
@TestPropertySource(properties = "hephaestus.integration.slack.conversation-ingest.enabled=true")
class SlackConsentLifecycleE2EIntegrationTest extends BaseIntegrationTest {

    private static final String TEAM = "T1";
    private static final String C1 = "C1";
    private static final String C2 = "C2"; // sibling channel, must stay isolated from C1's erasures

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
    private SlackMonitoredChannelRepository monitoredChannelRepository;

    @Autowired
    private SlackChannelConsentEventRepository consentEventRepository;

    @Autowired
    private SlackParticipantConsentRepository participantConsentRepository;

    @Autowired
    private SlackMessageRepository messageRepository;

    @Autowired
    private SlackThreadRepository threadRepository;

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
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ReviewJobService agentJobService;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private AgentActions actions;

    @Autowired
    private ApplicationContext context;

    private ActionExecutor consentExecutor;

    private SlackConversationTestSupport support;
    private SlackMessageService slackMessageService;
    private SlackChannelConsentService consentService;
    private SlackIngestService ingestService;
    private SlackInteractivityHandler handler;

    private long workspaceId;
    private long u1MemberId;
    private long u2MemberId;
    private Practice practice;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        support = new SlackConversationTestSupport(jdbcTemplate);

        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-e2e-lifecycle"));
        workspaceId = workspace.getId();
        u1MemberId = SlackConversationTestSupport.accountWithIdentity(accounts, identities, provider, 100L, "e2e-u1");
        memberships.createMembership(workspace, u1MemberId, Membership.WorkspaceRole.MEMBER);
        u2MemberId = SlackConversationTestSupport.accountWithIdentity(accounts, identities, provider, 200L, "e2e-u2");
        memberships.createMembership(workspace, u2MemberId, Membership.WorkspaceRole.MEMBER);
        practice = savePractice(workspace);

        SlackWorkspaceResolver workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        when(workspaceResolver.whileConnected(eq(workspaceId), eq(TEAM), any()))
                .thenAnswer(inv -> Optional.ofNullable(new TransactionTemplate(transactionManager)
                        .execute(status -> ((Supplier<?>) inv.getArgument(2)).get())));
        SlackMentorIdentityResolver identityResolver = mock(SlackMentorIdentityResolver.class);
        SlackRosterIdentityResolver roster = mock(SlackRosterIdentityResolver.class);
        when(roster.displayName(workspaceId, TEAM, "U1")).thenReturn(Optional.of("A person"));
        when(roster.humanAccountId(workspaceId, TEAM, "U1", "A person")).thenReturn(Optional.of(u1MemberId));
        when(roster.displayName(workspaceId, TEAM, "U2")).thenReturn(Optional.of("A person"));
        when(roster.humanAccountId(workspaceId, TEAM, "U2", "A person")).thenReturn(Optional.of(u2MemberId));
        when(identityResolver.resolveAccountId(workspaceId, TEAM, "U1")).thenReturn(Optional.of(u1MemberId));
        when(identityResolver.resolveAccountId(workspaceId, TEAM, "U2")).thenReturn(Optional.of(u2MemberId));
        when(identityResolver.resolveActiveAccountId(any(Long.class), any(), any()))
                .thenReturn(Optional.empty());

        slackMessageService = mock(SlackMessageService.class);
        when(slackMessageService.isConnected(workspaceId)).thenReturn(true);
        when(slackMessageService.lookupConversationDetailed(eq(workspaceId), any()))
                .thenAnswer(inv ->
                        new SlackMessageService.ConversationLookup.Found(new SlackMessageService.SlackConversationInfo(
                                inv.getArgument(1), "channel", false, true, false)));
        when(slackMessageService.postMessage(eq(workspaceId), any(), any(), any(), any(), any()))
                .thenAnswer(inv -> new SlackMessageService.Posted(
                        inv.getArgument(1), Instant.now().getEpochSecond() + ".123456"));
        ConnectionService connectionService = mock(ConnectionService.class);
        when(connectionService.findSlackConfig(workspaceId))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig(TEAM, null, null, Set.of())));

        ingestService = new SlackIngestService(
                workspaceResolver,
                monitoredChannelRepository,
                new SlackChannelConsentGate(monitoredChannelRepository),
                new SlackParticipantConsentGate(participantConsentRepository),
                messageRepository,
                threadRepository,
                roster,
                conversationFeedbackErasure,
                mock(ProviderEventStore.class),
                /* conversationIngestEnabled */ true,
                new TransactionTemplate(transactionManager));
        consentService = new SlackChannelConsentService(
                monitoredChannelRepository,
                consentEventRepository,
                participantConsentRepository,
                ingestService,
                slackMessageService,
                connectionService,
                new TransactionTemplate(transactionManager),
                event -> {},
                new SlackChannelJoinActions(actions, consentEventRepository, mapper),
                new SlackConsentActions(actions, consentEventRepository, mapper),
                workspaceResolver);
        consentExecutor = ActionTestSupport.withAdapter(
                context,
                SlackConsentTestSupport.adapter(
                        slackMessageService,
                        new SlackChannelActivation(monitoredChannelRepository, consentEventRepository, event -> {}),
                        mapper));
        handler = new SlackInteractivityHandler(
                workspaceResolver,
                identityResolver,
                mock(SlackAppHomeService.class),
                new SlackParticipantConsentService(participantConsentRepository),
                new SlackPersonErasureService(
                        messageRepository,
                        threadRepository,
                        conversationFeedbackErasure,
                        mock(AgentActions.class),
                        connectionService,
                        mock(GitProviderRegistry.class)),
                new SlackInteractiveReplyAdapter(slackMessageService),
                mock(SlackMentorService.class),
                mock(WeeklyDigestSubscriptions.class));
    }

    @Test
    @DisplayName("register → activate → forward-only ingest → review → person opt-out → revoke, asserted at every hop")
    void fullConsentLifecycleComposes() {

        SlackMonitoredChannelDTO registered =
                consentService.register(workspaceId, C1, "general").channel();
        assertThat(registered.consentState()).isEqualTo(ConsentState.PENDING);
        assertThat(currentChannel(C1).getConsentAnnouncedAt()).isNull();
        consentService.register(workspaceId, C2, "random");

        consentService.transition(workspaceId, C1, ConsentState.ACTIVE, "pilot go");
        assertThat(currentChannel(C1).getConsentState()).isEqualTo(ConsentState.ACTIVATING);
        assertThat(currentChannel(C1).getConsentAnnouncedAt()).isNull();
        ingestService.ingestChannelMessage(
                TEAM, C1, Instant.now().getEpochSecond() + ".000100", null, "U1", "not active yet");
        assertThat(messageCount(C1)).isZero();
        ActionTestSupport.drain(consentExecutor, jdbcTemplate);
        assertThat(currentChannel(C1).getConsentState()).isEqualTo(ConsentState.ACTIVE);
        Instant announcedAt = currentChannel(C1).getConsentAnnouncedAt();
        assertThat(announcedAt).isNotNull();
        // Activation opens the monitoring period at the later of the post and the request: never before the post.
        assertThat(currentChannel(C1).getIngestAfter()).isNotNull().isAfterOrEqualTo(announcedAt);

        var fallbackText = ArgumentCaptor.forClass(String.class);
        verify(slackMessageService)
                .postMessage(eq(workspaceId), eq(C1), isNull(), any(), fallbackText.capture(), any());
        assertThat(fallbackText.getValue())
                .contains("Hephaestus is now active in this channel")
                .contains("does not read earlier history");
        assertThat(auditToStates(C1))
                .containsExactly(ConsentState.PENDING, ConsentState.ACTIVATING, ConsentState.ACTIVE);

        long announcedEpoch = announcedAt.getEpochSecond();
        String tsAfter = (announcedEpoch + 30) + ".000100";
        String tsBefore = (announcedEpoch - 300) + ".000100";
        ingestService.ingestChannelMessage(TEAM, C1, tsAfter, null, "U1", "after announcement");
        ingestService.ingestChannelMessage(TEAM, C1, tsBefore, null, "U1", "pre-announcement backlog");
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, tsAfter))
                .isTrue();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, tsBefore))
                .isFalse();

        long baseSecond = Instant.now().getEpochSecond() - 1200; // 20 min ago → past quiescence
        String rootTs = baseSecond + ".000000";
        String lastTs = (baseSecond + 3) + ".000000";
        support.seedThread(workspaceId, C1, rootTs, lastTs, 4, "{" + u1MemberId + "," + u2MemberId + "}");
        for (long s = baseSecond; s <= baseSecond + 3; s++) {
            support.seedMessage(workspaceId, TEAM, C1, s + ".000000", s == baseSecond ? null : rootTs, "turn");
        }
        long settledThreadId = threadRepository
                .findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(workspaceId, C1, rootTs)
                .orElseThrow()
                .getId();
        SlackConversationTestSupport.BoundConversation u1Conv = seedBoundConversation(settledThreadId, u1MemberId);
        UUID u1Obs = u1Conv.resultId();
        UUID u1Fb = u1Conv.feedbackId();
        SlackConversationTestSupport.BoundConversation u2Conv = seedBoundConversation(settledThreadId, u2MemberId);
        UUID u2Obs = u2Conv.resultId();
        UUID u2Fb = u2Conv.feedbackId();

        var candidate = threadRepository
                .findConsentedCandidateRow(workspaceId, settledThreadId)
                .orElseThrow();
        assertThat(candidate.getWorkspaceId()).isEqualTo(workspaceId);
        assertThat(candidate.getParticipantAccountIds()).containsExactlyInAnyOrder(u1MemberId, u2MemberId);

        handler.handleBlockActions(optOut("U1"));
        assertThat(participantConsentRepository.existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(
                        workspaceId, "U1"))
                .isTrue();
        String tsAfterOptOut = (announcedEpoch + 60) + ".000100";
        ingestService.ingestChannelMessage(TEAM, C1, tsAfterOptOut, null, "U1", "post opt-out");
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, tsAfterOptOut))
                .isFalse();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, tsAfter))
                .isFalse();
        assertThat(participantIds(settledThreadId)).containsExactly(u2MemberId);
        assertThat(practiceResultRepository.findById(u1Obs)).isEmpty();
        assertThat(feedbackRepository.findById(u1Fb)).isEmpty();
        assertThat(practiceResultRepository.findById(u2Obs)).isPresent();
        assertThat(feedbackRepository.findById(u2Fb)).isPresent();

        consentService.transition(workspaceId, C2, ConsentState.ACTIVE, "sibling active");
        ActionTestSupport.drain(consentExecutor, jdbcTemplate);
        support.seedThread(workspaceId, C2, "700.0", "700.0", 1, "{" + u2MemberId + "}");
        support.seedMessage(workspaceId, TEAM, C2, "700.0", null, "sibling msg");

        // This manually constructed service needs the transaction normally supplied by its Spring proxy.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s -> consentService.transition(workspaceId, C1, ConsentState.REVOKED, "wind down"));
        assertThat(currentChannel(C1).getConsentState()).isEqualTo(ConsentState.REVOKED);
        assertThat(threadCount(C1)).isZero();
        assertThat(messageCount(C1)).isZero();
        assertThat(practiceResultRepository.findById(u2Obs)).isEmpty();
        assertThat(auditToStates(C1))
                .containsExactly(
                        ConsentState.PENDING, ConsentState.ACTIVATING, ConsentState.ACTIVE, ConsentState.REVOKED);
        assertThat(currentChannel(C2).getConsentState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(messageCount(C2)).isEqualTo(1);
    }

    @Test
    void retryConfirmsAnEarlierPostWithoutReadingTheOffPeriod() {
        Instant postedAt = Instant.ofEpochSecond(Instant.now().getEpochSecond() - 3600);
        String postedTs = SlackTs.ofInstant(postedAt);
        var marker = UUID.randomUUID();
        var channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setSlackTeamId(TEAM);
        channel.setSlackChannelId(C1);
        channel.setConsentState(ConsentState.FAILED);
        channel.setConsentAnnouncementId(marker);
        monitoredChannelRepository.saveAndFlush(channel);
        for (var transition : List.of(
                new SlackChannelConsentEvent(workspaceId, C1, null, ConsentState.PENDING, null, null),
                new SlackChannelConsentEvent(
                        workspaceId, C1, ConsentState.PENDING, ConsentState.ACTIVATING, null, null),
                new SlackChannelConsentEvent(
                        workspaceId, C1, ConsentState.ACTIVATING, ConsentState.FAILED, null, null))) {
            jdbcTemplate.update(
                    """
                INSERT INTO slack_channel_consent_event(workspace_id,slack_channel_id,from_state,to_state,created_at)
                VALUES (?,?,?,?,?)
                """,
                    workspaceId,
                    C1,
                    transition.getFromState() == null
                            ? null
                            : Objects.requireNonNull(transition.getFromState()).name(),
                    transition.getToState().name(),
                    Timestamp.from(postedAt.minusSeconds(1)));
        }
        when(slackMessageService.readWorkspaceMessages(eq(workspaceId), eq(C1), any(), any(), any(), eq(20)))
                .thenReturn(new SlackMessageService.Matches(List.of(postedTs), false));

        consentService.transition(workspaceId, C1, ConsentState.ACTIVE, "retry after bot re-invited");
        var request =
                consentEventRepository
                        .findByWorkspaceIdAndSlackChannelIdOrderByCreatedAtAscIdAsc(workspaceId, C1)
                        .stream()
                        .filter(event -> event.getToState() == ConsentState.ACTIVATING)
                        .max(Comparator.comparing(event -> Objects.requireNonNull(event.getId())))
                        .orElseThrow();
        Instant requestedAt = Objects.requireNonNull(request.getCreatedAt());
        ActionTestSupport.drain(consentExecutor, jdbcTemplate);

        var active = currentChannel(C1);
        assertThat(active.getConsentState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(active.getConsentAnnouncedAt()).isEqualTo(postedAt);
        // The earlier post confirms disclosure; the monitoring period still starts at the retry request.
        assertThat(active.getIngestAfter()).isEqualTo(requestedAt);
        assertThat(active.getLastHistorySyncedTs()).isEqualTo(SlackTs.ofInstant(requestedAt));
        verify(slackMessageService, never()).postMessage(eq(workspaceId), any(), any(), any(), any(), any());
        // A history window fetched before the retry cannot reopen the failed interval when it finishes late.
        assertThat(monitoredChannelRepository.advanceHistoryWatermark(
                        workspaceId, C1, SlackTs.ofInstant(postedAt.plusSeconds(1800)), requestedAt.plusSeconds(1)))
                .isZero();
        assertThat(currentChannel(C1).getLastHistorySyncedTs()).isEqualTo(SlackTs.ofInstant(requestedAt));
        ingestService.ingestChannelMessage(
                TEAM, C1, SlackTs.ofInstant(postedAt.plusSeconds(1800)), null, "U1", "off-period message");
        assertThat(messageCount(C1)).isZero();
        ingestService.ingestChannelMessage(
                TEAM, C1, SlackTs.ofInstant(requestedAt.plusSeconds(30)), null, "U1", "after retry");
        assertThat(messageCount(C1)).isEqualTo(1);
    }

    @Test
    void delayedReplyToAnOldThreadIsStoredAfterReconciliationAdvancedPastIt() {
        consentService.register(workspaceId, C1, "general");
        consentService.transition(workspaceId, C1, ConsentState.ACTIVE, "pilot go");
        ActionTestSupport.drain(consentExecutor, jdbcTemplate);
        var active = currentChannel(C1);
        assertThat(active.getConsentState()).isEqualTo(ConsentState.ACTIVE);
        Instant ingestAfter = Objects.requireNonNull(active.getIngestAfter());

        // A completed reconciliation window moves the watermark past a reply that Slack delivers late.
        long replyEpoch = ingestAfter.getEpochSecond() + 30;
        String oldRootTs = (ingestAfter.getEpochSecond() - 3600) + ".000100";
        String replyTs = replyEpoch + ".000200";
        String watermark = (replyEpoch + 300) + ".000000";
        assertThat(monitoredChannelRepository.advanceHistoryWatermark(
                        workspaceId, C1, watermark, ingestAfter.plusSeconds(300)))
                .isEqualTo(1);
        assertThat(currentChannel(C1).getLastHistorySyncedTs()).isEqualTo(watermark);

        ingestService.ingestChannelMessage(TEAM, C1, replyTs, oldRootTs, "U1", "late reply to an old thread");

        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, replyTs))
                .isTrue();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT slack_thread_ts FROM slack_message "
                                + "WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?",
                        String.class,
                        workspaceId,
                        C1,
                        replyTs))
                .isEqualTo(oldRootTs);
        // The floor is the monitoring period, not the watermark: a reply from before activation still stays out.
        String beforePeriodTs = (ingestAfter.getEpochSecond() - 60) + ".000300";
        ingestService.ingestChannelMessage(TEAM, C1, beforePeriodTs, oldRootTs, "U1", "written before monitoring");
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(workspaceId, C1, beforePeriodTs))
                .isFalse();
    }

    @Test
    void aStoredMonitoringFloorWithoutADisclosureDoesNotPermitIngestion() {
        Instant floor = Instant.now().minusSeconds(60);
        var channel = new SlackMonitoredChannel();
        channel.setWorkspaceId(workspaceId);
        channel.setSlackTeamId(TEAM);
        channel.setSlackChannelId(C1);
        channel.setConsentState(ConsentState.ACTIVE);
        channel.setIngestAfter(floor);
        monitoredChannelRepository.saveAndFlush(channel);

        assertThat(monitoredChannelRepository.readIngestAfter(workspaceId, C1)).isEmpty();
        ingestService.ingestChannelMessage(
                TEAM, C1, SlackTs.ofInstant(floor.plusSeconds(30)), null, "U1", "no disclosure was posted");
        assertThat(messageCount(C1)).isZero();
    }

    private long threadCount(String channelId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ?",
                Long.class,
                workspaceId,
                channelId);
        return n == null ? 0 : n;
    }

    private ObjectNode optOut(String slackUserId) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "block_actions");
        payload.putObject("team").put("id", TEAM);
        payload.putObject("user").put("id", slackUserId);
        payload.putObject("channel").put("id", C1);
        ArrayNode actions = payload.putArray("actions");
        ObjectNode action = actions.addObject();
        action.put("action_id", SlackAppHomeService.ACTION_CHANNEL_MESSAGES_OPT_OUT);
        action.put("value", "false");
        return payload;
    }

    private SlackMonitoredChannel currentChannel(String channelId) {
        return monitoredChannelRepository
                .findByWorkspaceIdAndSlackChannelId(workspaceId, channelId)
                .orElseThrow();
    }

    private List<ConsentState> auditToStates(String channelId) {
        return consentEventRepository
                .findByWorkspaceIdAndSlackChannelIdOrderByCreatedAtAscIdAsc(workspaceId, channelId)
                .stream()
                .map(e -> e.getToState())
                .toList();
    }

    private List<Long> participantIds(long threadId) {
        Long[] ids = jdbcTemplate.queryForObject(
                "SELECT participant_account_ids FROM slack_thread WHERE id = ?",
                (rs, n) -> (Long[]) rs.getArray(1).getArray(),
                threadId);
        return List.of(ids);
    }

    private long messageCount(String channelId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ?",
                Long.class,
                workspaceId,
                channelId);
        return n == null ? 0 : n;
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

    private Practice savePractice(Workspace ws) {
        return SlackConversationTestSupport.newPractice(practiceVersions, ws, "e2e-practice");
    }
}
