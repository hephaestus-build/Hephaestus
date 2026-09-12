package de.tum.cit.aet.hephaestus.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadCandidate;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobService;
import de.tum.cit.aet.hephaestus.agent.job.ConversationReviewSubmitter;
import de.tum.cit.aet.hephaestus.agent.job.conversation.ConversationThreadTriggerScheduler;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationCommand;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackMonitoredChannelDTO;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackChannelConsentEventRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannelRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackParticipantConsentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackIngestService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentGate;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackParticipantConsentService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackPersonErasureService;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackWorkspaceResolver;
import de.tum.cit.aet.hephaestus.integration.slack.interactivity.SlackInteractivityHandler;
import de.tum.cit.aet.hephaestus.integration.slack.mentor.SlackMentorIdentityResolver;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.onboarding.SlackAppHomeService;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
    private ConversationThreadTriggerScheduler scheduler;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AgentJobService agentJobService;

    @MockitoSpyBean
    private ConversationReviewSubmitter conversationReviewSubmitter;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private SlackConversationTestSupport support;
    private SlackMessageService slackMessageService;
    private SlackChannelConsentService consentService;
    private SlackIngestService ingestService;
    private SlackInteractivityHandler handler;

    private long workspaceId;
    private long u1MemberId;
    private long u2MemberId;
    private Practice practice;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        support = new SlackConversationTestSupport(jdbcTemplate);
        support.ensureUnmappedSlackThreadColumns();

        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        Workspace workspace = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("slack-e2e-lifecycle"));
        workspaceId = workspace.getId();
        u1MemberId = userRepository
                .save(TestUserFactory.createUser(100L, "e2e-u1", provider))
                .getId();
        u2MemberId = userRepository
                .save(TestUserFactory.createUser(200L, "e2e-u2", provider))
                .getId();
        practice = savePractice(workspace);
        job = newJob(workspace);

        SlackWorkspaceResolver workspaceResolver = mock(SlackWorkspaceResolver.class);
        when(workspaceResolver.resolveWorkspaceId(TEAM)).thenReturn(Optional.of(workspaceId));
        SlackMentorIdentityResolver identityResolver = mock(SlackMentorIdentityResolver.class);
        when(identityResolver.resolveMemberId(workspaceId, TEAM, "U1")).thenReturn(Optional.of(u1MemberId));
        when(identityResolver.resolveMemberId(workspaceId, TEAM, "U2")).thenReturn(Optional.of(u2MemberId));
        when(identityResolver.resolveActiveMemberId(any(Long.class), any(), any()))
                .thenReturn(Optional.empty());

        slackMessageService = mock(SlackMessageService.class);
        ConnectionService connectionService = mock(ConnectionService.class);
        when(connectionService.findSlackNotificationConfig(workspaceId))
                .thenReturn(Optional.of(new ConnectionConfig.SlackConfig(TEAM, null, null, null, null, Set.of())));

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
        consentService = new SlackChannelConsentService(
                monitoredChannelRepository,
                consentEventRepository,
                participantConsentRepository,
                ingestService,
                slackMessageService,
                connectionService,
                userRepository,
                new SlackHephaestusUiLinks(
                        new WorkspaceSummaryQuery() {
                            @Override
                            public Optional<WorkspaceSummary> findById(long workspaceId) {
                                return Optional.of(new WorkspaceSummary(workspaceId, "hephaestus", "Hephaestus"));
                            }

                            @Override
                            public Map<Long, WorkspaceSummary> findAllByIds(Collection<Long> workspaceIds) {
                                Map<Long, WorkspaceSummary> found = new HashMap<>();
                                workspaceIds.forEach(
                                        id -> found.put(id, findById(id).orElseThrow()));
                                return found;
                            }
                        },
                        "https://hephaestus.test"),
                new TransactionTemplate(transactionManager),
                event -> {} // activation kick intentionally not asserted here
                );
        handler = new SlackInteractivityHandler(
                workspaceResolver,
                identityResolver,
                mock(ResearchParticipationCommand.class),
                mock(SlackAppHomeService.class),
                new SlackParticipantConsentService(participantConsentRepository),
                new SlackPersonErasureService(messageRepository, threadRepository, conversationFeedbackErasure),
                slackMessageService);
    }

    @Test
    @DisplayName("register → activate → forward-only ingest → detect → person opt-out → revoke, asserted at every hop")
    void fullConsentLifecycleComposes() {

        SlackMonitoredChannelDTO registered =
                consentService.register(workspaceId, C1, "general").channel();
        assertThat(registered.consentState()).isEqualTo(ConsentState.PENDING);
        assertThat(currentChannel(C1).getConsentAnnouncedAt()).isNull();
        consentService.register(workspaceId, C2, "random");

        consentService.transition(workspaceId, C1, ConsentState.ACTIVE, "pilot go");
        assertThat(currentChannel(C1).getConsentState()).isEqualTo(ConsentState.ACTIVE);
        Instant announcedAt = currentChannel(C1).getConsentAnnouncedAt();
        assertThat(announcedAt).isNotNull();

        var fallbackText = ArgumentCaptor.forClass(String.class);
        verify(slackMessageService).sendForWorkspace(eq(workspaceId), eq(C1), any(), fallbackText.capture());
        assertThat(fallbackText.getValue())
                .contains("Hephaestus is now active in this channel")
                .contains("does not read earlier history");
        assertThat(auditToStates(C1)).containsExactly(ConsentState.ACTIVE);

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
            support.seedMessage(workspaceId, C1, s + ".000000", s == baseSecond ? null : rootTs, "turn");
        }
        long settledThreadId = threadRepository
                .findByWorkspaceIdAndSlackChannelIdAndSlackThreadTs(workspaceId, C1, rootTs)
                .orElseThrow()
                .getId();
        SlackConversationTestSupport.BoundConversation u1Conv = seedBoundConversation(settledThreadId, u1MemberId);
        UUID u1Obs = u1Conv.observationId();
        UUID u1Fb = u1Conv.feedbackId();
        SlackConversationTestSupport.BoundConversation u2Conv = seedBoundConversation(settledThreadId, u2MemberId);
        UUID u2Obs = u2Conv.observationId();
        UUID u2Fb = u2Conv.feedbackId();

        doReturn(2L).when(conversationReviewSubmitter).submitAndSettle(any(), any());
        scheduler.detectNow();
        ArgumentCaptor<ConversationThreadCandidate> captor = ArgumentCaptor.forClass(ConversationThreadCandidate.class);
        verify(conversationReviewSubmitter).submitAndSettle(captor.capture(), any());
        assertThat(captor.getValue().workspaceId()).isEqualTo(workspaceId);
        assertThat(captor.getValue().participantMemberIds()).containsExactlyInAnyOrder(u1MemberId, u2MemberId);

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
        assertThat(observationRepository.findById(u1Obs)).isEmpty();
        assertThat(feedbackRepository.findById(u1Fb)).isEmpty();
        assertThat(observationRepository.findById(u2Obs)).isPresent();
        assertThat(feedbackRepository.findById(u2Fb)).isPresent();

        consentService.transition(workspaceId, C2, ConsentState.ACTIVE, "sibling active");
        support.seedThread(workspaceId, C2, "700.0", "700.0", 1, "{" + u2MemberId + "}");
        support.seedMessage(workspaceId, C2, "700.0", null, "sibling msg");

        // This manually constructed service needs the transaction normally supplied by its Spring proxy.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        s -> consentService.transition(workspaceId, C1, ConsentState.REVOKED, "wind down"));
        assertThat(currentChannel(C1).getConsentState()).isEqualTo(ConsentState.REVOKED);
        assertThat(threadCount(C1)).isZero();
        assertThat(messageCount(C1)).isZero();
        assertThat(observationRepository.findById(u2Obs)).isEmpty(); // remaining C1 derived rows erased too
        assertThat(auditToStates(C1)).containsExactly(ConsentState.ACTIVE, ConsentState.REVOKED);
        assertThat(currentChannel(C2).getConsentState()).isEqualTo(ConsentState.ACTIVE);
        assertThat(messageCount(C2)).isEqualTo(1);
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
                "SELECT participant_member_ids FROM slack_thread WHERE id = ?",
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
                observationRepository,
                feedbackRepository,
                feedbackObservationRepository,
                workspaceId,
                job.getId(),
                practice.getId(),
                threadId,
                aboutUserId);
    }

    private Practice savePractice(Workspace ws) {
        return SlackConversationTestSupport.newPractice(practiceRepository, ws, "e2e-practice");
    }

    private AgentJob newJob(Workspace ws) {
        return SlackConversationTestSupport.newConversationReviewJob(agentJobRepository, ws);
    }
}
