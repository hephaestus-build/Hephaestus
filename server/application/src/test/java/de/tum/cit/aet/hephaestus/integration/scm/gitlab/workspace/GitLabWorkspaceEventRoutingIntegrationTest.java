package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ConsumerSubjectMath;
import de.tum.cit.aet.hephaestus.integration.core.consumer.IntegrationNatsConsumer;
import de.tum.cit.aet.hephaestus.integration.core.consumer.NatsConnectionProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublishers;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookIngestPipeline;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabDescendantGroupResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitlabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitlabSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActivationService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryAlreadyMonitoredException;
import de.tum.cit.aet.hephaestus.workspace.exception.RepositoryProviderNotConnectedException;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewRepositoryTargetRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.Nats;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitLab webhook events from a real JetStream stream reach the database while the workspace's full sync is
 * held open, and a new group's webhook opens only once its scope consumer is ready. Each test drives one
 * lifecycle; only the GitLab API is stubbed.
 */
class GitLabWorkspaceEventRoutingIntegrationTest extends BaseIntegrationTest {

    private static final String STREAM = "gitlab";
    private static final String SERVER_URL = "https://gitlab.lrz.de";
    private static final String GROUP = "hephaestustest";
    private static final String REPOSITORY = "hephaestustest/demo-repository";
    private static final String WEBHOOK_URL = "http://localhost:8080/webhooks/gitlab";
    private static final String ISSUE_SUBJECT = "gitlab.hephaestustest.demo-repository.issue";
    private static final String NOTE_SUBJECT = "gitlab.hephaestustest.demo-repository.note";
    private static final Long NATIVE_ISSUE_ID = 422296L;
    private static final Long NATIVE_NOTE_ID = 4406174L;
    private static final long GROUP_ID = 42L;
    private static final long WEBHOOK_ID = 99L;
    private static final long LEGACY_WEBHOOK_ID = 7L;
    private static final String NESTED_PROJECT = "hephaestustest/test-subgroup/nested-demo";
    private static final long NESTED_PROJECT_ID = 777L;
    private static final long SUBGROUP_ID = 319723L;
    private static final long MEMBER_USER_ID = 18024L;

    @DynamicPropertySource
    static void natsProperties(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.sync.nats.enabled", () -> "true");
        registry.add("hephaestus.sync.nats.server", NatsTestContainer::getServerUrl);
        registry.add("hephaestus.sync.run-on-startup", () -> "true");
        // Production receives webhooks in its own container; this test publishes to the stream itself.
        registry.add("hephaestus.runtime.webhook.enabled", () -> "false");
    }

    /** The test profile's executor drops connect-time initialization; these tests run it. */
    @TestBean(name = "monitoringExecutor")
    private @Nullable ThreadPoolTaskExecutor monitoringExecutor;

    static ThreadPoolTaskExecutor monitoringExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("test-monitoring-");
        executor.initialize();
        return executor;
    }

    @MockitoBean
    private GitLabSyncServiceHolder gitLabSyncServices;

    @MockitoBean
    private GitLabWebhookClient gitLabWebhookClient;

    @MockitoBean
    private GitLabTokenRotationClient gitLabTokenRotationClient;

    @MockitoBean
    private GitLabDeletionSweepService deletionSweepService;

    @MockitoSpyBean
    private GitLabProjectSyncService projectSyncService;

    @MockitoSpyBean
    private GitLabTeamSyncService teamSyncService;

    @MockitoSpyBean
    private GitLabUserService gitLabUserService;

    @Autowired
    private WorkspaceActivationService workspaceActivationService;

    @Autowired
    private GitlabDataSyncScheduler gitlabDataSyncScheduler;

    @Autowired
    private SyncJobService syncJobService;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private IntegrationNatsConsumer integrationNatsConsumer;

    @Autowired
    private NatsConnectionProperties natsConnectionProperties;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private IssueCommentRepository issueCommentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GitLabRepositoryMonitors repositoryMonitors;

    @Autowired
    private PracticeReviewRepositoryTargetRepository practiceReviewRepositoryTargetRepository;

    @Autowired
    private WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;

    @Autowired
    private PracticeReviewCoverageService practiceReviewCoverageService;

    @Autowired
    private GitLabRouteCredential routeCredential;

    @Autowired
    private GitlabSubjectKeyDeriver subjectKeyDeriver;

    @Autowired
    private WebhookProperties webhookProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private UserRepository userRepository;

    /** The group hooks GitLab holds, as the stubbed webhook API reports them. */
    private final List<GitLabWebhookClient.WebhookInfo> hooks = new CopyOnWriteArrayList<>();

    private final List<GitLabWebhookClient.WebhookConfig> registeredHooks = new CopyOnWriteArrayList<>();
    private final AtomicLong nextHookId = new AtomicLong(WEBHOOK_ID);
    private final String deliveryPrefix = UUID.randomUUID().toString();
    private @Nullable GitLabConnectionWebhookController ingress;

    private final GitLabGroupSyncService groupSync = mock(GitLabGroupSyncService.class);
    private final GitLabIssueTypeSyncService issueTypeSync = mock(GitLabIssueTypeSyncService.class);
    private final CountDownLatch syncBlocked = new CountDownLatch(1);
    private final CountDownLatch releaseSync = new CountDownLatch(1);
    private final AtomicReference<@Nullable Set<String>> filterWhenRegistered = new AtomicReference<>();

    private io.nats.client.Connection natsConnection;
    private JetStreamManagement jsm;
    private @Nullable Workspace workspace;
    private @Nullable Thread restart;

    @BeforeEach
    void setUp() throws Exception {
        databaseTestUtils.cleanDatabase();
        natsConnection = Nats.connect(NatsTestContainer.getServerUrl());
        jsm = natsConnection.jetStreamManagement();

        when(gitLabSyncServices.getGroupSyncService()).thenReturn(groupSync);
        when(gitLabSyncServices.getIssueTypeSyncService()).thenReturn(issueTypeSync);
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(invocation -> GitLabSyncResult.completed(List.of(discoveredRepository()), 1, 0, 0));
        doReturn(Map.of(
                        MEMBER_USER_ID,
                        new GitLabUserLookup(
                                "gid://gitlab/User/" + MEMBER_USER_ID,
                                "ga84xah",
                                "Felix Dietrich",
                                null,
                                SERVER_URL + "/ga84xah",
                                null)))
                .when(gitLabUserService)
                .fetchCanonicalUsers(anyLong(), anyCollection());
        when(gitLabTokenRotationClient.getTokenInfo(anyLong()))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(1L, "hephaestus", null));
        when(gitLabWebhookClient.lookupGroup(anyLong(), eq(GROUP)))
                .thenReturn(new GitLabWebhookClient.GroupInfo(GROUP_ID, "HephaestusTest", GROUP));
        when(gitLabWebhookClient.registerGroupWebhook(anyLong(), eq(GROUP_ID), any()))
                .thenAnswer(invocation -> registerHook(invocation.getArgument(2)));
        when(gitLabWebhookClient.listGroupWebhooks(anyLong(), eq(GROUP_ID)))
                .thenAnswer(invocation -> List.copyOf(hooks));
        when(gitLabWebhookClient.getGroupWebhook(anyLong(), eq(GROUP_ID), anyLong()))
                .thenAnswer(invocation -> hooks.stream()
                        .filter(hook -> hook.id() == (long) invocation.getArgument(2))
                        .findFirst());
        doAnswer(invocation -> hooks.removeIf(hook -> hook.id() == (long) invocation.getArgument(2)))
                .when(gitLabWebhookClient)
                .deregisterGroupWebhook(anyLong(), eq(GROUP_ID), anyLong());
        ingress = new GitLabConnectionWebhookController(
                new WebhookIngestPipeline(
                        List.of(),
                        List.of(subjectKeyDeriver),
                        JetStreamPublishers.of(natsConnection, webhookProperties),
                        objectMapper),
                routeCredential,
                subjectKeyDeriver);
        when(deletionSweepService.sweepScope(anyLong(), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, false));
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseSync.countDown();
        if (restart != null) {
            restart.join(Duration.ofSeconds(10));
        }
        awaitMonitoringIdle();
        if (workspace != null) {
            integrationNatsConsumer.stopConsumingScope(workspace.getId());
        }
        if (jsm.getStreamNames().contains(STREAM)) {
            jsm.deleteStream(STREAM);
        }
        natsConnection.close();
    }

    @Test
    void shouldPersistEventsDeliveredOnWebhookRegistrationWhenGroupIsFirstConnected() throws Exception {
        createStream();
        when(gitLabWebhookClient.registerGroupWebhook(anyLong(), eq(GROUP_ID), any()))
                .thenAnswer(invocation -> {
                    GitLabWebhookClient.WebhookInfo hook = registerHook(invocation.getArgument(2));
                    publish(ISSUE_SUBJECT, "gitlab/issue.open.json");
                    publish(NOTE_SUBJECT, "gitlab/note.issue.create.json");
                    return hook;
                });
        holdFullSync();

        Workspace connected = connectGroup();

        assertThat(syncBlocked.await(30, SECONDS)).isTrue();
        assertThat(filterWhenRegistered.get()).isEqualTo(expectedFilter(connected));
        assertThat(webhookId(connected)).isEqualTo(WEBHOOK_ID);
        assertIssueAndNotePersistedOnce();
    }

    @Test
    void shouldPersistQueuedEventsWhileRestartedWorkspaceIsSyncing() throws Exception {
        createStream();
        Workspace restarted = persistedWorkspace();
        repositoryToMonitorRepository.save(WorkspaceTestFixtures.repositoryMonitor(restarted, REPOSITORY));
        discoveredRepository();
        jsm.addOrUpdateConsumer(
                STREAM,
                ConsumerConfiguration.builder()
                        .durable(ConsumerSubjectMath.scopeConsumerName(
                                        natsConnectionProperties.durableConsumerName(), restarted.getId())
                                + "-" + STREAM)
                        .filterSubjects(legacyFilter().toArray(String[]::new))
                        .deliverPolicy(DeliverPolicy.New)
                        .build());
        publish(ISSUE_SUBJECT, "gitlab/issue.open.json");
        publish(NOTE_SUBJECT, "gitlab/note.issue.create.json");
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL))).thenAnswer(invocation -> {
            syncBlocked.countDown();
            releaseSync.await();
            return GitLabSyncResult.completed(List.of(discoveredRepository()), 1, 0, 0);
        });

        restart = Thread.ofVirtual().start(() -> workspaceActivationService.activateWorkspace(restarted));

        assertThat(syncBlocked.await(30, SECONDS)).isTrue();
        assertIssueAndNotePersistedOnce();
        assertThat(filterSubjects()).isEqualTo(expectedFilter(restarted));
    }

    @Test
    void shouldOpenWebhookOnReconcileOnceScopeConsumerIsReady() throws Exception {
        Workspace connected = connectGroup();
        awaitMonitoringIdle();

        assertThat(webhookId(connected)).isNull();

        assertThat(reconcile(connected)).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(webhookId(connected)).isNull();
        verify(gitLabWebhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());

        createStream();
        assertThat(reconcile(connected)).isEqualTo(SyncJobStatus.SUCCEEDED);

        assertThat(webhookId(connected)).isEqualTo(WEBHOOK_ID);
        assertThat(filterWhenRegistered.get()).isEqualTo(expectedFilter(connected));
        verify(gitLabWebhookClient, times(1)).registerGroupWebhook(anyLong(), anyLong(), any());
        publish(ISSUE_SUBJECT, "gitlab/issue.open.json");
        publish(NOTE_SUBJECT, "gitlab/note.issue.create.json");
        assertIssueAndNotePersistedOnce();
    }

    @Test
    void shouldOpenWebhookOnReconcileOnceDiscoveryListsEveryProject() throws Exception {
        createStream();
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(invocation -> GitLabSyncResult.withErrors(List.of(discoveredRepository()), 1, 0, 0, 0));
        Workspace connected = connectGroup();
        awaitMonitoringIdle();

        assertThat(webhookId(connected)).isNull();
        assertThat(filterSubjects()).isEqualTo(expectedFilter(connected));
        verify(gitLabWebhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());

        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(invocation -> GitLabSyncResult.completed(List.of(discoveredRepository()), 1, 0, 0));
        reconcile(connected);

        assertThat(webhookId(connected)).isEqualTo(WEBHOOK_ID);
        verify(gitLabWebhookClient, times(1)).registerGroupWebhook(anyLong(), anyLong(), any());
    }

    @Test
    void shouldHandleANestedProjectAndItsFirstWorkWhenTheyArriveThroughTheConnectionRoute() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        Team subgroup = subgroupTeam();
        String subgroupPath = GROUP + "/test-subgroup";
        doReturn(Optional.of(reported(NESTED_PROJECT_ID, NESTED_PROJECT)))
                .when(projectSyncService)
                .fetchProjectById(connected.getId(), NESTED_PROJECT_ID);
        doReturn(Optional.of(new GitLabDescendantGroupResponse(
                        "gid://gitlab/Group/" + SUBGROUP_ID, subgroupPath, "test-subgroup", null, null, null, null)))
                .when(teamSyncService)
                .fetchGroup(connected.getId(), SUBGROUP_ID);
        doReturn(Optional.of(List.of(member("MAINTAINER", 40))))
                .when(teamSyncService)
                .fetchMembership(connected.getId(), subgroupPath, SUBGROUP_ID, MEMBER_USER_ID, false);

        deliver(connected, projectCreate(NESTED_PROJECT, NESTED_PROJECT_ID), "project");
        deliver(connected, onProject(fixture("gitlab/issue.open.json"), NESTED_PROJECT, NESTED_PROJECT_ID), "issue");
        deliver(
                connected,
                onProject(fixture("gitlab/note.issue.create.json"), NESTED_PROJECT, NESTED_PROJECT_ID),
                "note");
        // GitLab retries a delivery with the same idempotency key; it is published once.
        deliver(
                connected,
                onProject(fixture("gitlab/note.issue.create.json"), NESTED_PROJECT, NESTED_PROJECT_ID),
                "note");
        deliver(connected, fixture("gitlab/member.add.json"), "member");

        assertIssueAndNotePersistedOnce();
        Repository nested =
                repositoryRepository.findByNameWithOwner(NESTED_PROJECT).orElseThrow();
        assertThat(nested.getNativeId()).isEqualTo(NESTED_PROJECT_ID);
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), NESTED_PROJECT))
                .isTrue();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Long userId = userRepository
                    .findByNativeIdAndProviderId(MEMBER_USER_ID, gitLabProviderId())
                    .orElseThrow()
                    .getId();
            assertThat(teamMembershipRepository.findById(new TeamMembership.Id(subgroup.getId(), userId)))
                    .hasValueSatisfying(
                            membership -> assertThat(membership.getRole()).isEqualTo(TeamMembership.Role.MAINTAINER));
        });
        Long userId = userRepository
                .findByNativeIdAndProviderId(MEMBER_USER_ID, gitLabProviderId())
                .orElseThrow()
                .getId();
        TeamMembership.Id membershipId = new TeamMembership.Id(subgroup.getId(), userId);
        assertThat(userRepository.findById(userId).orElseThrow().getName()).isEqualTo("Felix Dietrich");

        // A removal GitLab contradicts keeps the access GitLab reports.
        ObjectNode removal = (ObjectNode) fixture("gitlab/member.add.json");
        removal.put("event_name", "user_remove_from_group");
        deliver(connected, removal, "member-removal");
        awaitAcknowledged();
        assertThat(teamMembershipRepository.findById(membershipId)).isPresent();

        // Once GitLab reports no membership, even an addition claiming access removes it.
        doReturn(Optional.of(List.of()))
                .when(teamSyncService)
                .fetchMembership(connected.getId(), subgroupPath, SUBGROUP_ID, MEMBER_USER_ID, false);
        ObjectNode addition = (ObjectNode) fixture("gitlab/member.add.json");
        addition.put("group_access", "Maintainer");
        deliver(connected, addition, "member-addition");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(teamMembershipRepository.findById(membershipId))
                        .isEmpty());
    }

    @Test
    void shouldNotLetOneConnectionActForAnotherOrOutsideItsGroup() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        Workspace other = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.gitLabPatWorkspace("othergroup"),
                SERVER_URL);
        Repository otherRepository = foreignRepository("othergroup/secret-project", 555L);
        repositoryToMonitorRepository.save(
                WorkspaceTestFixtures.repositoryMonitor(other, otherRepository.getNameWithOwner()));
        GitLabWebhookClient.WebhookConfig hook = registeredHooks.getLast();
        byte[] outside = bytes(onProject(fixture("gitlab/issue.open.json"), "othergroup/secret-project", 555L));

        assertThat(ingest(connectionId(other), routeOf(hook), hook.token(), SERVER_URL, "a-to-b", outside))
                .as("a credential replayed to another connection's endpoint")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ingest(
                        connectionId(connected),
                        routeOf(hook),
                        hook.token(),
                        "https://gitlab.example.com",
                        "forged",
                        outside))
                .as("an instance header that contradicts the signed origin")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ingest(connectionId(connected), routeOf(hook), hook.token(), SERVER_URL, "outside", outside))
                .isEqualTo(HttpStatus.ACCEPTED);

        awaitAcknowledged();
        assertThat(issueRepository.findAll()).noneMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(
                        connected.getId(), otherRepository.getNameWithOwner()))
                .isFalse();
    }

    @Test
    void shouldNotLetAConnectedPathCarryAnotherWorkspacesProjectId() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        Repository victim = foreignRepository("othergroup/secret-project", 555L);
        String forgedPath = GROUP + "/renamed-secret";
        doReturn(Optional.of(reported(555L, victim.getNameWithOwner())))
                .when(projectSyncService)
                .fetchProjectById(connected.getId(), 555L);
        doReturn(Optional.of(reported(discoveredRepository().getNativeId(), REPOSITORY)))
                .when(projectSyncService)
                .fetchProject(connected.getId(), REPOSITORY);
        Team foreignTeam = team(888L, "othergroup", "team");
        doReturn(Optional.of(new GitLabDescendantGroupResponse(
                        "gid://gitlab/Group/888", "othergroup/team", "team", null, null, null, null)))
                .when(teamSyncService)
                .fetchGroup(connected.getId(), 888L);

        ObjectNode rename = (ObjectNode) projectCreate(forgedPath, 555L);
        rename.put("event_name", "project_rename");
        rename.put("old_path_with_namespace", victim.getNameWithOwner());
        deliver(connected, rename, "forged-rename");
        ObjectNode transfer = rename.deepCopy();
        transfer.put("event_name", "project_transfer");
        deliver(connected, transfer, "forged-transfer");
        deliver(connected, onProject(fixture("gitlab/issue.open.json"), REPOSITORY, 555L), "forged-issue");
        ObjectNode subgroup = JsonNodeFactory.instance.objectNode();
        subgroup.put("event_name", "subgroup_create");
        subgroup.put("group_id", 888L);
        subgroup.put("full_path", GROUP + "/forged");
        subgroup.put("name", "forged");
        deliver(connected, subgroup, "forged-subgroup");

        awaitAcknowledged();
        Team untouched = teamRepository.findById(foreignTeam.getId()).orElseThrow();
        assertThat(untouched.getName()).isEqualTo("team");
        assertThat(untouched.getOrganization()).isEqualTo("othergroup");
        Repository unchanged = repositoryRepository.findById(victim.getId()).orElseThrow();
        assertThat(unchanged.getNameWithOwner()).isEqualTo("othergroup/secret-project");
        assertThat(unchanged.getNativeId()).isEqualTo(555L);
        assertThat(repositoryRepository.findByNameWithOwner(forgedPath)).isEmpty();
        assertThat(issueRepository.findAll()).noneMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
    }

    @Test
    void shouldStopMonitoringAProjectMovedOutAfterItsNewGroupRecordedTheMove() throws Exception {
        assertMoveOutStopsOnlyThisWorkspacesMonitor(true);
    }

    @Test
    void shouldStopMonitoringAProjectMovedOutBeforeItsNewGroupRecordsTheMove() throws Exception {
        assertMoveOutStopsOnlyThisWorkspacesMonitor(false);
    }

    /**
     * The project leaves the connected group for another workspace's group. That workspace handling the move first is
     * simulated by storing what its admission stores: the shared row at the new path; this workspace's monitor then has
     * only its native id to be found by. Handled first here, the monitor has no native id, as one created before ids
     * were recorded, and is found through the stored row of the same project.
     */
    private void assertMoveOutStopsOnlyThisWorkspacesMonitor(boolean destinationFirst) throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        Repository project = discoveredRepository();
        long projectId = project.getNativeId();
        String moved = "othergroup/demo-repository";
        Workspace destination = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.gitLabPatWorkspace("othergroup"),
                SERVER_URL);
        RepositoryToMonitor destinationMonitor = WorkspaceTestFixtures.repositoryMonitor(destination, moved);
        destinationMonitor.setNativeId(projectId);
        repositoryToMonitorRepository.save(destinationMonitor);
        RepositoryToMonitor own = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY)
                .orElseThrow();
        assertThat(own.getNativeId()).isEqualTo(projectId);
        if (destinationFirst) {
            project.setNameWithOwner(moved);
            repositoryRepository.save(project);
        } else {
            own.setNativeId(null);
            repositoryToMonitorRepository.save(own);
        }
        doReturn(Optional.of(reported(projectId, moved)))
                .when(projectSyncService)
                .fetchProjectById(connected.getId(), projectId);
        ObjectNode transfer = (ObjectNode) projectCreate(moved, projectId);
        transfer.put("event_name", "project_transfer");

        deliver(connected, transfer, "moved-out");

        awaitAcknowledged();
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isFalse();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(connected.getId(), projectId))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(destination.getId(), moved))
                .isTrue();
        assertThat(repositoryRepository.findById(project.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo(destinationFirst ? moved : REPOSITORY);
    }

    @Test
    void shouldFollowARenameAnotherWorkspaceRecordedFirstWithASelectedMonitor() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.setRepositorySelection(RepositorySelection.SELECTED);
        workspaceRepository.save(selecting);
        Repository project = discoveredRepository();
        long projectId = project.getNativeId();
        String renamed = GROUP + "/renamed-repository";
        project.setNameWithOwner(renamed);
        repositoryRepository.save(project);
        doReturn(Optional.of(reported(projectId, renamed)))
                .when(projectSyncService)
                .fetchProjectById(workspaceId, projectId);
        ObjectNode rename = (ObjectNode) projectCreate(renamed, projectId);
        rename.put("event_name", "project_rename");

        deliver(connected, rename, "renamed-elsewhere-first");

        awaitAcknowledged();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, projectId))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getNameWithOwner()).isEqualTo(renamed));
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY))
                .isFalse();
        assertThat(repositoryRepository.findById(project.getId()).orElseThrow().getNameWithOwner())
                .isEqualTo(renamed);
    }

    @Test
    void shouldKeepOneMonitorWhenWorkAtTheNewPathArrivesBeforeTheRename() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Repository project = discoveredRepository();
        long projectId = project.getNativeId();
        long original = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.getReviewSettings().setRepositoryCoverageMode(ReviewRepositoryMode.SELECTED);
        workspaceRepository.save(selecting);
        practiceReviewRepositoryTargetRepository.save(
                new PracticeReviewRepositoryTarget(workspaceId, original, List.of()));
        String renamed = GROUP + "/renamed-repository";
        doReturn(Optional.of(reported(projectId, renamed)))
                .when(projectSyncService)
                .fetchProject(workspaceId, renamed);
        doReturn(Optional.of(reported(projectId, renamed)))
                .when(projectSyncService)
                .fetchProjectById(workspaceId, projectId);

        deliver(connected, onProject(fixture("gitlab/issue.open.json"), renamed, projectId), "work-at-new-path");
        awaitAcknowledged();

        // Before the rename event: the work event alone must have moved the original monitor and its review target.
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, projectId))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isEqualTo(original);
                    assertThat(monitor.getNameWithOwner()).isEqualTo(renamed);
                });
        assertThat(issueRepository.findAll()).anyMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
        Workspace reviewed = workspaceRepository.findById(workspaceId).orElseThrow();
        assertThat(practiceReviewCoverageService.scope(reviewed).repositories())
                .extracting(ReviewRepositoryTarget::nameWithOwner)
                .containsExactly(renamed);
        assertThat(practiceReviewCoverageService
                        .assess(reviewed, renamed, "main", null, true)
                        .repositoryMatched())
                .isTrue();
        assertThat(practiceReviewCoverageService
                        .assess(reviewed, REPOSITORY, "main", null, true)
                        .repositoryMatched())
                .isFalse();

        ObjectNode rename = (ObjectNode) projectCreate(renamed, projectId);
        rename.put("event_name", "project_rename");
        deliver(connected, rename, "rename-after-work");
        awaitAcknowledged();

        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, projectId))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isEqualTo(original);
                    assertThat(monitor.getNameWithOwner()).isEqualTo(renamed);
                });
    }

    @Test
    void shouldFollowARenameADiscoveryListsWithTheExistingMonitor() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Repository project = discoveredRepository();
        long original = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        String renamed = GROUP + "/renamed-repository";
        project.setNameWithOwner(renamed);
        Repository listed = repositoryRepository.save(project);

        assertThat(repositoryMonitors.monitorAllowed(
                        workspaceRepository.findById(workspaceId).orElseThrow(), List.of(listed)))
                .isZero();

        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, project.getNativeId()))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isEqualTo(original);
                    assertThat(monitor.getNameWithOwner()).isEqualTo(renamed);
                });
    }

    @Test
    void shouldMergeThisWorkspacesDuplicateMonitorsIntoTheFirstOnTheNextReport() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Repository project = discoveredRepository();
        long projectId = project.getNativeId();
        String renamed = GROUP + "/renamed-repository";
        RepositoryToMonitor original = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow();
        original.setIssueSyncCursor("original-cursor");
        repositoryToMonitorRepository.save(original);
        RepositoryToMonitor duplicate = WorkspaceTestFixtures.repositoryMonitor(connected, renamed);
        duplicate.setNativeId(projectId);
        repositoryToMonitorRepository.save(duplicate);
        Workspace other = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.gitLabPatWorkspace("othergroup"),
                SERVER_URL);
        RepositoryToMonitor foreign = WorkspaceTestFixtures.repositoryMonitor(other, REPOSITORY);
        foreign.setNativeId(projectId);
        repositoryToMonitorRepository.save(foreign);
        practiceReviewRepositoryTargetRepository.save(
                new PracticeReviewRepositoryTarget(workspaceId, duplicate.getId(), List.of("main")));
        practiceReviewRepositoryTargetRepository.save(
                new PracticeReviewRepositoryTarget(other.getId(), foreign.getId(), List.of("develop")));
        project.setNameWithOwner(renamed);
        Repository listed = repositoryRepository.save(project);

        repositoryMonitors.monitorAllowed(
                workspaceRepository.findById(workspaceId).orElseThrow(), List.of(listed));

        // The review selection that lived only on the duplicate now applies to the kept monitor.
        assertThat(practiceReviewRepositoryTargetRepository.findByWorkspaceId(workspaceId))
                .singleElement()
                .satisfies(target -> {
                    assertThat(target.getRepositoryMonitorId()).isEqualTo(original.getId());
                    assertThat(target.getBaseBranches()).containsExactly("main");
                });
        assertThat(practiceReviewRepositoryTargetRepository.findByWorkspaceId(other.getId()))
                .singleElement()
                .satisfies(target -> assertThat(target.getBaseBranches()).containsExactly("develop"));
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, projectId))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isEqualTo(original.getId());
                    assertThat(monitor.getNameWithOwner()).isEqualTo(renamed);
                    assertThat(monitor.getIssueSyncCursor()).isEqualTo("original-cursor");
                });
        assertThat(repositoryToMonitorRepository.findById(foreign.getId()))
                .hasValueSatisfying(
                        monitor -> assertThat(monitor.getNameWithOwner()).isEqualTo(REPOSITORY));
    }

    @Test
    void shouldWriteNoMonitorForAProjectTheCurrentConnectionDoesNotCover() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long connectionId = connectionId(connected);
        IdentityProvider otherInstance = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.example.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.example.com")));
        Repository samePathElsewhere = foreignRepository(GROUP + "/on-another-instance", 901L);
        samePathElsewhere.setProvider(otherInstance);
        samePathElsewhere = repositoryRepository.save(samePathElsewhere);
        Repository outsideGroup = foreignRepository("othergroup/outside", 902L);
        Workspace loaded = workspaceRepository.findById(workspaceId).orElseThrow();

        assertThat(repositoryMonitors.monitorAllowed(loaded, List.of(samePathElsewhere, outsideGroup)))
                .isZero();

        // The selection stored when the lock is held decides, not a Workspace the transaction loaded before.
        Repository project = foreignRepository(NESTED_PROJECT, NESTED_PROJECT_ID);
        ExecutorService committer = Executors.newSingleThreadExecutor();
        try {
            Integer created = transactionTemplate.execute(status -> {
                Workspace managed = workspaceRepository.findById(workspaceId).orElseThrow();
                CompletableFuture.runAsync(
                                () -> transactionTemplate.executeWithoutResult(inner -> workspaceRepository
                                        .findById(workspaceId)
                                        .orElseThrow()
                                        .setRepositorySelection(RepositorySelection.SELECTED)),
                                committer)
                        .orTimeout(20, SECONDS)
                        .join();
                return repositoryMonitors.monitorAllowed(managed, List.of(project));
            });
            assertThat(created).isZero();
        } finally {
            committer.shutdownNow();
        }

        // A creator that saw the connection active waits for a disconnect holding the lock, then writes nothing.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> disconnect = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
                Connection connection =
                        connectionRepository.findById(connectionId).orElseThrow();
                ReflectionTestUtils.setField(connection, "state", IntegrationState.SUSPENDED);
                connectionRepository.save(connection);
                locked.countDown();
                awaitUninterruptibly(release);
            }));
            assertThat(locked.await(20, SECONDS)).isTrue();
            Future<Integer> creator = threads.submit(() -> repositoryMonitors.monitorAll(loaded, List.of(project)));
            assertThatThrownBy(() -> creator.get(1, SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            disconnect.get(20, SECONDS);

            assertThat(creator.get(20, SECONDS)).isZero();
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThatThrownBy(() -> workspaceRepositoryMonitorService.addRepositoryToMonitor(
                        connected.getWorkspaceSlug(), NESTED_PROJECT))
                .isInstanceOf(RepositoryProviderNotConnectedException.class);
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspaceId, NESTED_PROJECT))
                .isFalse();
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspaceId))
                .extracting(RepositoryToMonitor::getNativeId)
                .doesNotContain(901L, 902L, NESTED_PROJECT_ID);
    }

    @Test
    void shouldNotAddByHandAProjectTheWorkspaceMonitorsUnderAnotherPath() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long connectionId = connectionId(connected);
        Repository project = discoveredRepository();
        String renamed = GROUP + "/renamed-repository";
        project.setNameWithOwner(renamed);
        repositoryRepository.save(project);

        assertThatThrownBy(() ->
                        workspaceRepositoryMonitorService.addRepositoryToMonitor(connected.getWorkspaceSlug(), renamed))
                .isInstanceOf(RepositoryAlreadyMonitoredException.class);
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, project.getNativeId()))
                .hasSize(1);

        // Adding by hand waits on the same lifecycle lock as every automatic creator.
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
                locked.countDown();
                awaitUninterruptibly(release);
            }));
            assertThat(locked.await(20, SECONDS)).isTrue();
            Future<?> manual = threads.submit(() -> workspaceRepositoryMonitorService.addRepositoryToMonitor(
                    connected.getWorkspaceSlug(), NESTED_PROJECT));
            assertThatThrownBy(() -> manual.get(1, SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(20, SECONDS);
            manual.get(20, SECONDS);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspaceId, NESTED_PROJECT))
                .isTrue();
    }

    @Test
    void shouldCreateOneMonitorWhenCreatorsWaitOnTheConnectionLifecycleLock() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long connectionId = connectionId(connected);
        Repository project = foreignRepository(NESTED_PROJECT, NESTED_PROJECT_ID);
        Workspace loaded = workspaceRepository.findById(workspaceId).orElseThrow();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
                locked.countDown();
                awaitUninterruptibly(release);
            }));
            assertThat(locked.await(20, SECONDS)).isTrue();
            Future<Integer> first = threads.submit(() -> repositoryMonitors.monitorAllowed(loaded, List.of(project)));
            Future<Integer> second = threads.submit(() -> repositoryMonitors.monitorAllowed(loaded, List.of(project)));

            // Neither creator may decide while the lifecycle lock is held elsewhere.
            assertThatThrownBy(() -> first.get(1, SECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            holder.get(20, SECONDS);

            assertThat(first.get(20, SECONDS) + second.get(20, SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            threads.shutdownNow();
        }
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, NESTED_PROJECT_ID))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getNameWithOwner()).isEqualTo(NESTED_PROJECT));
    }

    @Test
    void shouldNotResumeMonitoringWhenTheMonitorIsRemovedWhileADeliveryIsPrepared() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isTrue();
        // The project is moved out of the group while GitLab is asked for the users of work on it.
        doAnswer(invocation -> {
                    repositoryToMonitorRepository
                            .findByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY)
                            .ifPresent(repositoryToMonitorRepository::delete);
                    return Map.of();
                })
                .when(gitLabUserService)
                .fetchCanonicalUsers(eq(connected.getId()), anyCollection());

        deliver(connected, fixture("gitlab/issue.open.json"), "moved-while-prepared");

        awaitAcknowledged();
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isFalse();
        assertThat(issueRepository.findAll()).noneMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
    }

    @Test
    void shouldRedeliverWorkOnAProjectUntilGitLabCanReportIt() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        AtomicLong lookups = new AtomicLong();
        doAnswer(invocation -> {
                    if (lookups.incrementAndGet() == 1) {
                        throw new IllegalStateException("GitLab unavailable");
                    }
                    return Optional.of(reported(NESTED_PROJECT_ID, NESTED_PROJECT));
                })
                .when(projectSyncService)
                .fetchProject(connected.getId(), NESTED_PROJECT);

        deliver(connected, onProject(fixture("gitlab/issue.open.json"), NESTED_PROJECT, NESTED_PROJECT_ID), "retry");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(issueRepository.findAll())
                        .anyMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId())));
        assertThat(lookups.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldNotAdmitTheSameGroupPathOnAnotherInstance() throws Exception {
        createStream();
        Workspace elsewhere = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.gitLabPatWorkspace(GROUP).withSlug("same-path-elsewhere"),
                "https://gitlab.example.com");
        workspace = elsewhere;
        connectionService.updateConfig(
                elsewhere.getId(),
                IntegrationKind.GITLAB,
                config -> ((ConnectionConfig.GitLabConfig) config).withGitlabGroupId(GROUP_ID));
        discoveredRepository();
        repositoryToMonitorRepository.save(WorkspaceTestFixtures.repositoryMonitor(elsewhere, REPOSITORY));
        integrationNatsConsumer.establishScopeConsumer(elsewhere.getId(), STREAM);
        long connectionId = connectionId(elsewhere);
        GitLabRouteCredential.Issued issued = routeCredential.issue(new GitLabRouteCredential.Route(
                connectionId, elsewhere.getId(), "https://gitlab.example.com", GROUP_ID, GROUP));

        assertThat(ingest(
                        connectionId,
                        routeOf(issued),
                        issued.token(),
                        "https://gitlab.example.com",
                        "elsewhere",
                        bytes(fixture("gitlab/issue.open.json"))))
                .isEqualTo(HttpStatus.ACCEPTED);

        awaitAcknowledged();
        assertThat(issueRepository.findAll()).noneMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
    }

    @Test
    void shouldAcknowledgeWithoutHandlingWhenTheRouteIsNoLongerActive() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        GitLabWebhookClient.WebhookConfig hook = registeredHooks.getLast();
        Connection connection =
                connectionRepository.findById(connectionId(connected)).orElseThrow();
        ReflectionTestUtils.setField(connection, "state", IntegrationState.SUSPENDED);
        connectionRepository.save(connection);

        assertThat(ingest(
                        connectionId(connected),
                        routeOf(hook),
                        hook.token(),
                        SERVER_URL,
                        "inactive",
                        bytes(fixture("gitlab/issue.open.json"))))
                .isEqualTo(HttpStatus.ACCEPTED);
        GitLabRouteCredential.Issued unknownRoute = routeCredential.issue(
                new GitLabRouteCredential.Route(999_999L, connected.getId(), SERVER_URL, GROUP_ID, GROUP));
        assertThat(ingest(
                        999_999L,
                        routeOf(unknownRoute),
                        unknownRoute.token(),
                        SERVER_URL,
                        "unknown",
                        bytes(fixture("gitlab/issue.open.json"))))
                .isEqualTo(HttpStatus.ACCEPTED);

        awaitAcknowledged();
        assertThat(issueRepository.findAll()).noneMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
    }

    private GitLabWebhookClient.WebhookInfo registerHook(GitLabWebhookClient.WebhookConfig config) throws Exception {
        filterWhenRegistered.set(filterSubjects());
        registeredHooks.add(config);
        GitLabWebhookClient.WebhookInfo hook =
                new GitLabWebhookClient.WebhookInfo(nextHookId.getAndIncrement(), config.url());
        hooks.add(hook);
        return hook;
    }

    /** Delivers {@code payload} the way GitLab sends it to the connection's registered hook. */
    private void deliver(Workspace target, JsonNode payload, String deliveryKey) throws Exception {
        GitLabWebhookClient.WebhookConfig hook = registeredHooks.getLast();
        assertThat(hook.url()).contains("/connections/" + connectionId(target) + "/");
        assertThat(ingest(connectionId(target), routeOf(hook), hook.token(), SERVER_URL, deliveryKey, bytes(payload)))
                .isEqualTo(HttpStatus.ACCEPTED);
    }

    /** Posts {@code body} to the connection endpoint {@code route}, the {@code keyId/routeId} of a hook URL. */
    private HttpStatusCode ingest(
            long connectionId, String route, String token, String instance, String deliveryKey, byte[] body)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", GitLabConnectionWebhookController.PATH_PREFIX + connectionId + "/" + route);
        request.setContentType("application/json");
        request.setContent(body);
        request.addHeader("X-Gitlab-Token", token);
        request.addHeader("X-Gitlab-Instance", instance);
        request.addHeader("Idempotency-Key", deliveryPrefix + "-" + deliveryKey);
        String[] segments = route.split("/");
        return Objects.requireNonNull(ingress)
                .ingest(connectionId, segments[0], segments[1], request)
                .getStatusCode();
    }

    private static String routeOf(GitLabWebhookClient.WebhookConfig hook) {
        String[] segments = hook.url().split("/");
        return segments[segments.length - 2] + "/" + segments[segments.length - 1];
    }

    private static String routeOf(GitLabRouteCredential.Issued issued) {
        return issued.keyId() + "/" + issued.routeId();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(20, SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long connectionId(Workspace target) {
        return Objects.requireNonNull(connectionRepository
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        target.getId(), IntegrationKind.GITLAB, IntegrationState.ACTIVE)
                .orElseGet(() ->
                        connectionRepository.findByWorkspaceId(target.getId()).getFirst())
                .getId());
    }

    private void awaitAcknowledged() {
        await().atMost(Duration.ofSeconds(20))
                .until(() -> jsm.getConsumers(STREAM).stream()
                        .allMatch(durable -> durable.getNumPending() == 0 && durable.getNumAckPending() == 0));
    }

    private JsonNode fixture(String name) throws Exception {
        return objectMapper.readTree(new ClassPathResource(name).getContentAsByteArray());
    }

    private byte[] bytes(JsonNode payload) {
        return objectMapper.writeValueAsBytes(payload);
    }

    private static JsonNode projectCreate(String pathWithNamespace, long projectId) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("event_name", "project_create");
        payload.put("name", pathWithNamespace.substring(pathWithNamespace.lastIndexOf('/') + 1));
        payload.put("path", pathWithNamespace.substring(pathWithNamespace.lastIndexOf('/') + 1));
        payload.put("path_with_namespace", pathWithNamespace);
        payload.put("project_id", projectId);
        payload.put("project_visibility", "private");
        return payload;
    }

    /** {@code payload} as if it happened in the project at {@code pathWithNamespace}. */
    private static JsonNode onProject(JsonNode payload, String pathWithNamespace, long projectId) {
        ObjectNode project = (ObjectNode) payload.get("project");
        project.put("id", projectId);
        project.put("path_with_namespace", pathWithNamespace);
        project.put("web_url", SERVER_URL + "/" + pathWithNamespace);
        return payload;
    }

    /** The subgroup the member fixture names, as team sync stores it. */
    private Team subgroupTeam() {
        return team(SUBGROUP_ID, GROUP, "test-subgroup");
    }

    private Team team(long nativeId, String organization, String slug) {
        Team team = new Team();
        team.setNativeId(nativeId);
        team.setProvider(identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                .orElseThrow());
        team.setName(slug);
        team.setSlug(slug);
        team.setHtmlUrl(SERVER_URL + "/" + organization + "/" + slug);
        team.setOrganization(organization);
        return teamRepository.save(team);
    }

    /** A project as GitLab reports it. */
    private static GitLabProjectResponse reported(long nativeId, String fullPath) {
        return new GitLabProjectResponse(
                "gid://gitlab/Project/" + nativeId,
                fullPath,
                fullPath.substring(fullPath.lastIndexOf('/') + 1),
                SERVER_URL + "/" + fullPath,
                null,
                "private",
                false,
                null,
                null,
                null,
                null);
    }

    private static GitLabGroupMemberResponse member(String access, int level) {
        return new GitLabGroupMemberResponse(
                new GitLabGroupMemberResponse.GitLabMemberUser(
                        "gid://gitlab/User/" + MEMBER_USER_ID, "ga84xah", "Felix Dietrich", null, null),
                new GitLabGroupMemberResponse.GitLabAccessLevel(access, level));
    }

    /** A repository another workspace owns, outside this test's connected group. */
    private Repository foreignRepository(String nameWithOwner, long nativeId) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setName(nameWithOwner.substring(nameWithOwner.lastIndexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl(SERVER_URL + "/" + nameWithOwner);
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository.setDefaultBranch("main");
        repository.setCreatedAt(Instant.now());
        repository.setUpdatedAt(Instant.now());
        repository.setPushedAt(Instant.now());
        repository.setProvider(identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                .orElseThrow());
        return repositoryRepository.save(repository);
    }

    private long gitLabProviderId() {
        return Objects.requireNonNull(identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                .orElseThrow()
                .getId());
    }

    /** Connects a GitLab group the way workspace creation does, which starts its initialization. */
    private Workspace connectGroup() {
        Workspace saved = workspaceRepository.save(
                WorkspaceTestFixtures.gitLabPatWorkspace(GROUP).build());
        workspace = saved;
        connectionService.provisionPatConnection(
                saved,
                IntegrationKind.GITLAB,
                SERVER_URL,
                new ConnectionConfig.GitLabConfig(
                        SERVER_URL, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of()),
                "glpat-test",
                "event-routing-" + saved.getId());
        return saved;
    }

    /** A GitLab workspace connected by an earlier run, with its group webhook registered. */
    private Workspace persistedWorkspace() {
        Workspace saved = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository, connectionRepository, WorkspaceTestFixtures.gitLabPatWorkspace(GROUP), SERVER_URL);
        workspace = saved;
        connectionService.rotateBearerToken(saved.getId(), IntegrationKind.GITLAB, new BearerToken("glpat-test", null));
        connectionService.updateConfig(
                saved.getId(),
                IntegrationKind.GITLAB,
                config -> ((ConnectionConfig.GitLabConfig) config)
                        .withGitlabGroupId(GROUP_ID)
                        .withGitlabWebhookId(LEGACY_WEBHOOK_ID));
        hooks.add(new GitLabWebhookClient.WebhookInfo(LEGACY_WEBHOOK_ID, WEBHOOK_URL));
        return saved;
    }

    /** The rows GitLab project discovery writes for the group's one project. */
    private Repository discoveredRepository() {
        return repositoryRepository.findByNameWithOwner(REPOSITORY).orElseGet(() -> {
            IdentityProvider provider = identityProviderRepository
                    .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                    .orElseGet(() -> identityProviderRepository.save(
                            new IdentityProvider(IdentityProviderType.GITLAB, SERVER_URL)));
            Organization organization = new Organization();
            organization.setNativeId(GROUP_ID);
            organization.setLogin(GROUP);
            organization.setName("HephaestusTest");
            organization.setAvatarUrl("");
            organization.setHtmlUrl(SERVER_URL + "/" + GROUP);
            organization.setCreatedAt(Instant.now());
            organization.setUpdatedAt(Instant.now());
            organization.setProvider(provider);
            organization = organizationRepository.save(organization);

            Repository repository = new Repository();
            repository.setNativeId(246765L);
            repository.setName("demo-repository");
            repository.setNameWithOwner(REPOSITORY);
            repository.setHtmlUrl(SERVER_URL + "/" + REPOSITORY);
            repository.setVisibility(Repository.Visibility.PRIVATE);
            repository.setDefaultBranch("main");
            repository.setCreatedAt(Instant.now());
            repository.setUpdatedAt(Instant.now());
            repository.setPushedAt(Instant.now());
            repository.setOrganization(organization);
            repository.setProvider(provider);
            return repositoryRepository.save(repository);
        });
    }

    private void holdFullSync() {
        when(issueTypeSync.syncIssueTypesForGroup(anyLong(), anyString())).thenAnswer(invocation -> {
            syncBlocked.countDown();
            releaseSync.await();
            return 0;
        });
    }

    /** What an administrator's "Sync now" runs. */
    private SyncJobStatus reconcile(Workspace target) {
        Connection connection = connectionRepository
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        target.getId(), IntegrationKind.GITLAB, IntegrationState.ACTIVE)
                .orElseThrow();
        long connectionId = Objects.requireNonNull(connection.getId());
        syncJobService.run(
                new SyncJobRequest(
                        target.getId(),
                        connectionId,
                        IntegrationKind.GITLAB,
                        SyncJobType.RECONCILIATION,
                        SyncJobTrigger.MANUAL,
                        null),
                handle -> gitlabDataSyncScheduler.syncWorkspaceNow(target.getId(), handle, SyncJobType.RECONCILIATION));
        return syncJobRepository
                .findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(connectionId, SyncJobStatus.TERMINAL)
                .orElseThrow()
                .getStatus();
    }

    private @Nullable Long webhookId(Workspace target) {
        return connectionService
                .findActiveGitLabConfig(target.getId())
                .map(ConnectionConfig.GitLabConfig::gitlabWebhookId)
                .orElse(null);
    }

    private void awaitMonitoringIdle() {
        ThreadPoolTaskExecutor executor = Objects.requireNonNull(monitoringExecutor);
        await().atMost(Duration.ofSeconds(30))
                .until(() -> executor.getActiveCount() == 0 && executor.getQueueSize() == 0);
    }

    private void assertIssueAndNotePersistedOnce() throws Exception {
        await().atMost(Duration.ofSeconds(20)).until(() -> commentsWithNativeId() == 1);
        assertThat(issueRepository.findAll())
                .filteredOn(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()))
                .singleElement()
                .satisfies(issue -> assertThat(issue.getNumber()).isEqualTo(5));
        transactionTemplate.executeWithoutResult(status -> assertThat(issueCommentRepository.findAll())
                .filteredOn(comment -> NATIVE_NOTE_ID.equals(comment.getNativeId()))
                .singleElement()
                .extracting(IssueComment::getIssue)
                .satisfies(issue ->
                        assertThat(Objects.requireNonNull(issue).getNativeId()).isEqualTo(NATIVE_ISSUE_ID)));
        // The handler commits before it acknowledges, so the acknowledgement can trail the rows.
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            ConsumerInfo durable = jsm.getConsumers(STREAM).getFirst();
            return durable.getNumPending() == 0 && durable.getNumAckPending() == 0;
        });
        assertThat(jsm.getConsumers(STREAM).getFirst().getRedelivered()).isZero();
    }

    private long commentsWithNativeId() {
        return issueCommentRepository.findAll().stream()
                .filter(comment -> NATIVE_NOTE_ID.equals(comment.getNativeId()))
                .count();
    }

    /** What the durable of an earlier release subscribed: the monitored repository and the group tier. */
    private static Set<String> legacyFilter() {
        return Set.of(
                ConsumerSubjectMath.repositoryFilter(STREAM, REPOSITORY),
                ConsumerSubjectMath.organizationFilter(STREAM, GROUP));
    }

    private Set<String> expectedFilter(Workspace target) {
        Set<String> subjects = new HashSet<>(legacyFilter());
        subjects.add(ConsumerSubjectMath.connectionFilter(STREAM, connectionId(target)));
        return subjects;
    }

    private Set<String> filterSubjects() throws Exception {
        try {
            return jsm.getConsumers(STREAM).stream()
                    .flatMap(info -> Objects.requireNonNullElse(
                            info.getConsumerConfiguration().getFilterSubjects(), List.<String>of())
                            .stream())
                    .collect(Collectors.toSet());
        } catch (JetStreamApiException e) {
            return Set.of();
        }
    }

    private void createStream() throws Exception {
        jsm.addStream(StreamConfiguration.builder()
                .name(STREAM)
                .subjects(STREAM + ".>")
                .storageType(StorageType.Memory)
                .build());
    }

    private void publish(String subject, String fixture) throws Exception {
        natsConnection.jetStream().publish(subject, new ClassPathResource(fixture).getContentAsByteArray());
    }
}
