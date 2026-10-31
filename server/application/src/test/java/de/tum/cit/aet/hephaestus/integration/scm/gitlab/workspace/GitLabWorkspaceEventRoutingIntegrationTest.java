package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.webhook.WebhookProperties;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRequest;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.ConnectionHealth;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncStatusService;
import de.tum.cit.aet.hephaestus.integration.core.webhook.JetStreamPublishers;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookIngestPipeline;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabClosingIssueClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabHeadPipeline;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestReadinessReader;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiscussionSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabConnectionWebhookController;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabRouteCredential;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.webhook.GitLabSubjectKeyDeriver;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.GitLabRepositorySelectionService;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryAlreadyMonitoredException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryProviderNotConnectedException;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.WorkspaceRepositoryMonitorService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.OfflineNetwork;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActivationService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.dto.CreateWorkspaceRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceDTO;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class GitLabWorkspaceEventRoutingIntegrationTest extends BaseIntegrationTest {

    @BeforeAll
    static void declarePublicHosts() {
        OfflineNetwork.answer("gitlab.lrz.de", OfflineNetwork.PUBLIC_ADDRESS);
    }

    private static final String STREAM = "gitlab";
    private static final String SERVER_URL = "https://gitlab.lrz.de";
    private static final String GROUP = "hephaestustest";
    private static final String REPOSITORY = "hephaestustest/demo-repository";
    private static final Long NATIVE_ISSUE_ID = 422296L;
    private static final Long NATIVE_NOTE_ID = 4406174L;
    private static final long GROUP_ID = 42L;
    private static final long WEBHOOK_ID = 99L;
    private static final String NESTED_PROJECT = "hephaestustest/test-subgroup/nested-demo";
    private static final long NESTED_PROJECT_ID = 777L;
    private static final long SUBGROUP_ID = 319723L;
    private static final long MEMBER_USER_ID = 90001L;
    private static final long REPOSITORY_NATIVE_ID = 246765L;
    private static final int MERGE_REQUEST_IID = 2;
    private static final String HOOK_MERGE_COMMIT = "b186370e62e2ae348df64fb187c13aff4008457b";
    private static final Instant MERGED_AT = Instant.parse("2026-01-31T18:04:07Z");
    private static final int CLOSED_ISSUE_IID = 5;

    @DynamicPropertySource
    static void natsProperties(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.sync.nats.enabled", () -> "true");
        registry.add("hephaestus.sync.nats.server", NatsTestContainer::getServerUrl);
        registry.add("hephaestus.sync.run-on-startup", () -> "true");
        // Production receives webhooks in its own container; this test publishes to the stream
        // itself.
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

    @MockitoBean
    private GitLabMergeRequestReadinessReader readinessReader;

    @MockitoBean
    private GitLabClosingIssueClient closingIssueClient;

    @MockitoBean
    private GitLabDiscussionSyncService discussionSyncService;

    @MockitoSpyBean
    private GitLabProjectSyncService projectSyncService;

    @MockitoSpyBean
    private GitLabTeamSyncService teamSyncService;

    @MockitoSpyBean
    private GitLabUserService gitLabUserService;

    @Autowired
    private WorkspaceActivationService workspaceActivationService;

    @Autowired
    private GitLabDataSyncScheduler gitlabDataSyncScheduler;

    @Autowired
    private SyncJobService syncJobService;

    @Autowired
    private SyncJobRepository syncJobRepository;

    @Autowired
    private SyncStatusService syncStatusService;

    @MockitoSpyBean
    private IntegrationNatsConsumer integrationNatsConsumer;

    @Autowired
    private NatsConnectionProperties natsConnectionProperties;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ItemIndexService itemWriter;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionAuditRepository connectionAuditRepository;

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
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private RecordingScmEventListener scmEvents;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GitLabRepositoryMonitors repositoryMonitors;

    @Autowired
    private GitLabWorkspaceInitializationService initializationService;

    @Autowired
    private GitLabProjectAdmission admission;

    /** Workspace creation reads selected projects through it; GitLab itself is not reachable here. */
    @MockitoSpyBean
    private GitLabPreflightService preflightService;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private ReviewScopeRepositoryRepository practiceReviewRepositoryTargetRepository;

    @Autowired
    private WorkspaceRepositoryMonitorService workspaceRepositoryMonitorService;

    @Autowired
    private GitLabRepositorySelectionService gitLabRepositorySelectionService;

    @Autowired
    private PracticeReviewCoverageService practiceReviewCoverageService;

    @Autowired
    private GitLabRouteCredential routeCredential;

    @Autowired
    private GitLabSubjectKeyDeriver subjectKeyDeriver;

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
        doAnswer(invocation -> repositoryRepository
                        .findByNativeIdAndProviderId(REPOSITORY_NATIVE_ID, gitLabProviderId())
                        .map(repository -> reported(REPOSITORY_NATIVE_ID, repository.getNameWithOwner())))
                .when(projectSyncService)
                .fetchProjectById(anyLong(), eq(REPOSITORY_NATIVE_ID));
        // An explicit Add or a narrowing asks GitLab with the token and server one connection read stores; it answers
        // as each test arranged the scope's project read, and only for the stored token and server.
        doAnswer(invocation -> projectSyncService.fetchProject(
                        invocation.<Long>getArgument(0), invocation.<String>getArgument(3)))
                .when(projectSyncService)
                .fetchProject(anyLong(), eq("glpat-test"), eq(SERVER_URL), anyString());
        doReturn(Map.of(
                        MEMBER_USER_ID,
                        new GitLabUserLookup(
                                "gid://gitlab/User/" + MEMBER_USER_ID,
                                "ab12cde",
                                "Muster, Max (Erika)",
                                null,
                                SERVER_URL + "/ab12cde",
                                null,
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
                        List.of(), List.of(), JetStreamPublishers.of(natsConnection, webhookProperties), objectMapper),
                routeCredential,
                subjectKeyDeriver);
        when(deletionSweepService.sweepScope(anyLong(), any()))
                .thenReturn(new GitLabDeletionSweepService.SweepOutcome(0, 0, 0, false));
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
    void shouldRetainDiscoveryRateLimitInFinishedInitialJob() throws Exception {
        createStream();
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenReturn(GitLabSyncResult.aborted(GitLabSyncResult.Status.ABORTED_RATE_LIMIT, List.of(), 0, 0));

        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        SyncJob job = finishedInitialJob(connected);

        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(job.getErrorSummary()).isNull();
        assertThat(syncStatusService
                        .getStatus(
                                connected.getId(),
                                Objects.requireNonNull(job.getConnection().getId()))
                        .health())
                .isEqualTo(ConnectionHealth.DEGRADED);
        assertThat(SyncJobDTO.from(job).progress()).containsEntry("phase", "repositories");
        assertThat(SyncJobDTO.from(job).progress())
                .extractingByKey("currentStep")
                .asString()
                .containsIgnoringCase("rate limit");
    }

    @Test
    void shouldRetainPartialDiscoveryWithoutClaimingARateLimit() throws Exception {
        createStream();
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenReturn(GitLabSyncResult.withErrors(List.of(), 1, 1, 0, 0));

        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        SyncJob job = finishedInitialJob(connected);

        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(job.getErrorSummary()).isNull();
        assertThat(SyncJobDTO.from(job).progress())
                .containsEntry("phase", "repositories")
                .containsEntry("currentStep", "Project discovery completed with warnings.");
    }

    @Test
    void shouldKeepCleanInitialJobSuccessful() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();

        SyncJob job = finishedInitialJob(connected);
        assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
        assertThat(job.getErrorSummary()).isNull();
    }

    private SyncJob finishedInitialJob(Workspace target) {
        Connection connection = connectionRepository
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        target.getId(), IntegrationKind.GITLAB, IntegrationState.ACTIVE)
                .orElseThrow();
        SyncJob job = syncJobRepository
                .findFirstByConnection_IdAndStatusInOrderByFinishedAtDesc(
                        Objects.requireNonNull(connection.getId()), SyncJobStatus.TERMINAL)
                .orElseThrow();
        assertThat(job.getType()).isEqualTo(SyncJobType.INITIAL);
        assertThat(job.getTrigger()).isEqualTo(SyncJobTrigger.LIFECYCLE);
        return job;
    }

    @Test
    void shouldPersistEventsDeliveredOnWebhookRegistrationWhenGroupIsFirstConnected() throws Exception {
        createStream();
        when(gitLabWebhookClient.registerGroupWebhook(anyLong(), eq(GROUP_ID), any()))
                .thenAnswer(invocation -> {
                    GitLabWebhookClient.WebhookInfo hook = registerHook(invocation.getArgument(2));
                    Workspace connecting = Objects.requireNonNull(workspace);
                    deliver(connecting, fixture("gitlab/issue.open.json"), "issue");
                    deliver(connecting, fixture("gitlab/note.issue.create.json"), "note");
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
                                natsConnectionProperties.durableConsumerName(), restarted.getId(), STREAM))
                        .filterSubjects(expectedFilter(restarted).toArray(String[]::new))
                        .deliverPolicy(DeliverPolicy.New)
                        .build());
        deliverOnRoute(restarted, fixture("gitlab/issue.open.json"), "issue");
        deliverOnRoute(restarted, fixture("gitlab/note.issue.create.json"), "note");
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
        deliver(connected, fixture("gitlab/issue.open.json"), "issue");
        deliver(connected, fixture("gitlab/note.issue.create.json"), "note");
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
    void shouldKeepTheScopeRoutingWhenAMonitorRemovalRollsBack() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        Set<String> routed = expectedFilter(connected);
        await().atMost(Duration.ofSeconds(10)).until(() -> filterSubjects().equals(routed));
        clearInvocations(integrationNatsConsumer);

        transactionTemplate.executeWithoutResult(status -> {
            workspaceRepositoryMonitorService.removeRepositoryFromMonitor(connected.getWorkspaceSlug(), REPOSITORY);
            verify(integrationNatsConsumer, never()).updateScopeConsumer(connected.getId());
            status.setRollbackOnly();
        });

        verify(integrationNatsConsumer, never()).updateScopeConsumer(connected.getId());
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isTrue();
        assertThat(filterSubjects()).isEqualTo(routed);
    }

    @Test
    void shouldKeepTheConnectionRouteWhenARemovalAndAnAdditionCommit() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        // Monitors do not shape a GitLab scope's filter: route admission decides per delivery.
        Set<String> routed = expectedFilter(connected);
        await().atMost(Duration.ofSeconds(10)).until(() -> filterSubjects().equals(routed));
        clearInvocations(integrationNatsConsumer);

        transactionTemplate.executeWithoutResult(status -> {
            workspaceRepositoryMonitorService.removeRepositoryFromMonitor(connected.getWorkspaceSlug(), REPOSITORY);
            verify(integrationNatsConsumer, never()).updateScopeConsumer(connected.getId());
        });

        verify(integrationNatsConsumer, atLeastOnce()).updateScopeConsumer(connected.getId());
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isFalse();
        assertThat(filterSubjects()).isEqualTo(routed);
        clearInvocations(integrationNatsConsumer);

        doReturn(Optional.of(reported(REPOSITORY_NATIVE_ID, REPOSITORY)))
                .when(projectSyncService)
                .fetchProject(connected.getId(), REPOSITORY);

        transactionTemplate.executeWithoutResult(status -> {
            workspaceRepositoryMonitorService.addRepositoryToMonitor(connected.getWorkspaceSlug(), REPOSITORY);
            verify(integrationNatsConsumer, never()).updateScopeConsumer(connected.getId());
        });

        verify(integrationNatsConsumer, atLeastOnce()).updateScopeConsumer(connected.getId());
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY))
                .isTrue();
        assertThat(filterSubjects()).isEqualTo(routed);
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
        assertThat(userRepository.findById(userId).orElseThrow().getName()).isEqualTo("Muster, Max (Erika)");

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
    void shouldNotMonitorAnUnselectedSiblingWhenASelectedWorkspaceIsInitialized() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long selected = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.setRepositorySelection(RepositorySelection.SELECTED);
        workspaceRepository.save(selecting);
        long siblingId = 2_216_001L;
        Repository sibling = foreignRepository(GROUP + "/unselected-sibling", siblingId);
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(
                        invocation -> GitLabSyncResult.completed(List.of(discoveredRepository(), sibling), 1, 0, 0));

        initializationService.initialize(
                workspaceRepository.findById(workspaceId).orElseThrow());

        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, siblingId))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, REPOSITORY_NATIVE_ID))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getId()).isEqualTo(selected));
    }

    @Test
    void shouldStoreNothingFromANewSubgroupProjectOutsideTheSelectionWhileTheSelectedProjectSyncs() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.setRepositorySelection(RepositorySelection.SELECTED);
        workspaceRepository.save(selecting);
        long unselectedId = 2_216_002L;
        String unselected = GROUP + "/test-subgroup/unselected-project";
        // GitLab reports the project inside the connected group; only the selection keeps it out.
        doReturn(Optional.of(reported(unselectedId, unselected)))
                .when(projectSyncService)
                .fetchProjectById(workspaceId, unselectedId);
        doReturn(Optional.of(reported(unselectedId, unselected)))
                .when(projectSyncService)
                .fetchProject(workspaceId, unselected);

        deliver(connected, projectCreate(unselected, unselectedId), "unselected-created");
        long excludedIssueId = 2_216_003L;
        JsonNode excludedIssue = onProject(fixture("gitlab/issue.open.json"), unselected, unselectedId);
        ((ObjectNode) excludedIssue.get("object_attributes")).put("id", excludedIssueId);
        deliver(connected, excludedIssue, "unselected-issue");
        awaitAcknowledged();

        assertThat(repositoryRepository.findByNativeIdAndProviderId(unselectedId, gitLabProviderId()))
                .isEmpty();
        assertThat(repositoryRepository.findByNameWithOwner(unselected)).isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, unselectedId))
                .isEmpty();
        assertThat(issueRepository.findAll()).noneMatch(issue -> Objects.equals(excludedIssueId, issue.getNativeId()));

        deliver(connected, fixture("gitlab/issue.open.json"), "selected-issue");
        long selectedRepositoryId = discoveredRepository().getId();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(issueRepository.findByRepositoryIdAndNumber(selectedRepositoryId, 5))
                        .hasValueSatisfying(
                                issue -> assertThat(issue.getNativeId()).isEqualTo(NATIVE_ISSUE_ID)));
    }

    @Test
    void shouldStoreNothingFromANewProjectAfterAnAdminNarrowsTheWorkspaceToItsMonitoredProjects() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long kept = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        long actor = selectionAdmin("narrowing-admin");

        assertThat(selectProjects(connected, RepositorySelection.SELECTED, actor))
                .isEqualTo(RepositorySelection.SELECTED);

        long createdId = 2_216_040L;
        String created = GROUP + "/created-after-narrowing";
        doReturn(Optional.of(reported(createdId, created)))
                .when(projectSyncService)
                .fetchProjectById(workspaceId, createdId);
        doReturn(Optional.of(reported(createdId, created)))
                .when(projectSyncService)
                .fetchProject(workspaceId, created);
        deliver(connected, projectCreate(created, createdId), "created-after-narrowing");
        long createdIssueId = 2_216_041L;
        JsonNode createdIssue = onProject(fixture("gitlab/issue.open.json"), created, createdId);
        ((ObjectNode) createdIssue.get("object_attributes")).put("id", createdIssueId);
        deliver(connected, createdIssue, "issue-after-narrowing");
        awaitAcknowledged();
        long siblingId = 2_216_042L;
        Repository sibling = foreignRepository(GROUP + "/discovered-after-narrowing", siblingId);
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(
                        invocation -> GitLabSyncResult.completed(List.of(discoveredRepository(), sibling), 1, 0, 0));
        initializationService.initialize(
                workspaceRepository.findById(workspaceId).orElseThrow());

        assertThat(repositoryRepository.findByNativeIdAndProviderId(createdId, gitLabProviderId()))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, createdId))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, siblingId))
                .isEmpty();
        assertThat(issueRepository.findAll()).noneMatch(issue -> Objects.equals(createdIssueId, issue.getNativeId()));
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, REPOSITORY_NATIVE_ID))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getId()).isEqualTo(kept));

        deliver(connected, fixture("gitlab/issue.open.json"), "kept-issue-after-narrowing");
        long keptRepositoryId = discoveredRepository().getId();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(issueRepository.findByRepositoryIdAndNumber(keptRepositoryId, 5))
                        .hasValueSatisfying(
                                issue -> assertThat(issue.getNativeId()).isEqualTo(NATIVE_ISSUE_ID)));
    }

    @Test
    void shouldAdmitANewSiblingButKeepAStoppedProjectOutWhenAnAdminWidensTheSelection() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long kept = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        String stoppedPath = GROUP + "/stopped-before-widening";
        long stoppedId = 2_216_050L;
        Workspace holder = workspaceRepository.save(
                WorkspaceTestFixtures.gitLabPatWorkspace("selection-holder").build());
        RepositoryToMonitor held = new RepositoryToMonitor();
        held.setNameWithOwner(stoppedPath);
        held.setNativeId(stoppedId);
        held.setWorkspace(holder);
        held = repositoryToMonitorRepository.save(held);
        Repository stopped = foreignRepository(stoppedPath, stoppedId);
        RepositoryToMonitor stopping = new RepositoryToMonitor();
        stopping.setNameWithOwner(stoppedPath);
        stopping.setNativeId(stoppedId);
        stopping.setWorkspace(connected);
        repositoryToMonitorRepository.save(stopping);
        workspaceRepositoryMonitorService.removeRepositoryFromMonitor(connected.getWorkspaceSlug(), stoppedPath);
        long actor = selectionAdmin("widening-admin");

        assertThat(selectProjects(connected, RepositorySelection.SELECTED, actor))
                .isEqualTo(RepositorySelection.SELECTED);
        assertThat(selectProjects(connected, RepositorySelection.ALL, actor)).isEqualTo(RepositorySelection.ALL);

        long siblingId = 2_216_051L;
        Repository sibling = foreignRepository(GROUP + "/discovered-after-widening", siblingId);
        Repository rediscovered = repositoryRepository
                .findById(stopped.getId())
                .orElseGet(() -> foreignRepository(stoppedPath, stoppedId));
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(invocation ->
                        GitLabSyncResult.completed(List.of(discoveredRepository(), sibling, rediscovered), 1, 0, 0));
        initializationService.initialize(
                workspaceRepository.findById(workspaceId).orElseThrow());

        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, siblingId))
                .singleElement()
                .satisfies(monitor ->
                        assertThat(monitor.getNameWithOwner()).isEqualTo(GROUP + "/discovered-after-widening"));
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, stoppedId))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(workspaceId, stoppedPath))
                .isFalse();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, REPOSITORY_NATIVE_ID))
                .singleElement()
                .satisfies(monitor -> assertThat(monitor.getId()).isEqualTo(kept));
        assertThat(repositoryToMonitorRepository.findById(held.getId())).hasValueSatisfying(monitor -> {
            assertThat(monitor.getNativeId()).isEqualTo(stoppedId);
            assertThat(monitor.getNameWithOwner()).isEqualTo(stoppedPath);
        });
        assertThat(workspaceRepository.findById(holder.getId()).orElseThrow().getRepositorySelection())
                .isEqualTo(holder.getRepositorySelection());
        assertThat(selectionChanges(connected))
                .extracting(ConnectionAudit::getDetail, ConnectionAudit::getActorAccountId)
                .containsExactlyInAnyOrder(tuple("ALL → SELECTED", actor), tuple("SELECTED → ALL", actor));
    }

    @Test
    void shouldTakeAPathOnlyMonitorsIdFromGitLabRatherThanFromAMirrorWithTheSamePath() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        String legacy = GROUP + "/legacy-project";
        long mirroredId = 2_216_060L;
        long reportedId = 2_216_061L;
        // Another workspace recorded a different project at this path.
        Repository mirror = foreignRepository(legacy, mirroredId);
        RepositoryToMonitor unresolved = pathOnlyMonitor(connected, legacy);
        doReturn(Optional.of(reported(reportedId, legacy)))
                .when(projectSyncService)
                .fetchProject(workspaceId, legacy);
        long actor = selectionAdmin("resolving-admin");

        assertThat(selectProjects(connected, RepositorySelection.SELECTED, actor))
                .isEqualTo(RepositorySelection.SELECTED);

        assertThat(repositoryToMonitorRepository.findById(unresolved.getId())).hasValueSatisfying(monitor -> {
            assertThat(monitor.getNativeId()).isEqualTo(reportedId);
            assertThat(monitor.getNameWithOwner()).isEqualTo(legacy);
        });
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, mirroredId))
                .isEmpty();
        assertThat(repositoryRepository.findById(mirror.getId()))
                .hasValueSatisfying(
                        repository -> assertThat(repository.getNativeId()).isEqualTo(mirroredId));
        assertThat(workspaceRepository.findById(workspaceId).orElseThrow().getRepositorySelection())
                .isEqualTo(RepositorySelection.SELECTED);
        assertThat(selectionChanges(connected))
                .extracting(ConnectionAudit::getDetail, ConnectionAudit::getActorAccountId)
                .containsExactly(tuple("ALL → SELECTED", actor));
    }

    @Test
    void shouldChangeNothingWhenGitLabCannotConfirmOneOfThePathOnlyMonitors() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        String confirmed = GROUP + "/confirmed-legacy";
        String unknown = GROUP + "/unknown-legacy";
        RepositoryToMonitor first = pathOnlyMonitor(connected, confirmed);
        RepositoryToMonitor second = pathOnlyMonitor(connected, unknown);
        doReturn(Optional.of(reported(2_216_065L, confirmed)))
                .when(projectSyncService)
                .fetchProject(workspaceId, confirmed);
        doReturn(Optional.empty()).when(projectSyncService).fetchProject(workspaceId, unknown);
        long actor = selectionAdmin("unconfirmed-admin");

        assertThatThrownBy(() -> selectProjects(connected, RepositorySelection.SELECTED, actor))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(unknown);

        assertThat(repositoryToMonitorRepository.findAllById(List.of(first.getId(), second.getId())))
                .extracting(RepositoryToMonitor::getNativeId)
                .containsOnlyNulls();
        assertThat(workspaceRepository.findById(workspaceId).orElseThrow().getRepositorySelection())
                .isNotEqualTo(RepositorySelection.SELECTED);
        assertThat(selectionChanges(connected)).isEmpty();
    }

    @Test
    void shouldWriteNothingWhenTheConnectionIsPausedAndResumedWhileGitLabConfirmsAProject() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        String legacy = GROUP + "/held-legacy-project";
        long reportedId = 2_216_070L;
        RepositoryToMonitor unresolved = pathOnlyMonitor(connected, legacy);
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        doAnswer(invocation -> {
                    asked.countDown();
                    awaitUninterruptibly(answer);
                    return Optional.of(reported(reportedId, legacy));
                })
                .when(projectSyncService)
                .fetchProject(workspaceId, legacy);
        long actor = selectionAdmin("paused-narrowing-admin");
        ExecutorService narrowing = Executors.newSingleThreadExecutor();
        try {
            Future<RepositorySelection> narrowed =
                    narrowing.submit(() -> selectProjects(connected, RepositorySelection.SELECTED, actor));
            assertThat(asked.await(20, SECONDS)).isTrue();
            // The row returns to ACTIVE under the same id; GitLab answered for the connection before the pause.
            Connection active =
                    connectionRepository.findById(connectionId(connected)).orElseThrow();
            Connection paused = connectionService.transition(
                    active,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.SUSPENDED,
                            "TEST_SUSPENDED",
                            "SYSTEM",
                            "selection-race",
                            UUID.randomUUID().toString(),
                            "Paused while GitLab answered"));
            connectionService.transition(
                    paused,
                    new ConnectionService.TransitionRequest(
                            IntegrationState.ACTIVE,
                            "TEST_RESUMED",
                            "SYSTEM",
                            "selection-race",
                            UUID.randomUUID().toString(),
                            "Resumed while GitLab answered"));
            answer.countDown();

            assertThatThrownBy(() -> narrowed.get(20, SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("changed while we checked");
        } finally {
            answer.countDown();
            narrowing.shutdownNow();
        }
        awaitMonitoringIdle();

        assertThat(repositoryToMonitorRepository.findById(unresolved.getId()))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isNull());
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, reportedId))
                .isEmpty();
        assertThat(workspaceRepository.findById(workspaceId).orElseThrow().getRepositorySelection())
                .isNotEqualTo(RepositorySelection.SELECTED);
        assertThat(selectionChanges(connected)).isEmpty();
    }

    @Test
    void shouldNotNarrowOnAProjectStoppedAndAddedAgainWhileGitLabConfirmedIt() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        String legacy = GROUP + "/re-added-legacy-project";
        long reportedId = 2_216_080L;
        RepositoryToMonitor unresolved = pathOnlyMonitor(connected, legacy);
        CountDownLatch asked = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        AtomicInteger lookups = new AtomicInteger();
        // Only the narrowing's lookup is held; the Add after the Stop asks GitLab again and is answered at once.
        doAnswer(invocation -> {
                    if (lookups.getAndIncrement() == 0) {
                        asked.countDown();
                        awaitUninterruptibly(answer);
                    }
                    return Optional.of(reported(reportedId, legacy));
                })
                .when(projectSyncService)
                .fetchProject(workspaceId, legacy);
        long actor = selectionAdmin("re-added-narrowing-admin");
        ExecutorService narrowing = Executors.newSingleThreadExecutor();
        try {
            Future<RepositorySelection> narrowed =
                    narrowing.submit(() -> selectProjects(connected, RepositorySelection.SELECTED, actor));
            assertThat(asked.await(20, SECONDS)).isTrue();
            workspaceRepositoryMonitorService.removeRepositoryFromMonitor(connected.getWorkspaceSlug(), legacy);
            workspaceRepositoryMonitorService.addRepositoryToMonitor(connected.getWorkspaceSlug(), legacy);
            answer.countDown();

            assertThatThrownBy(() -> narrowed.get(20, SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("changed while we checked");
        } finally {
            answer.countDown();
            narrowing.shutdownNow();
        }
        awaitMonitoringIdle();

        assertThat(repositoryToMonitorRepository.findById(unresolved.getId())).isEmpty();
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, reportedId))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isNotEqualTo(unresolved.getId());
                    assertThat(monitor.getNameWithOwner()).isEqualTo(legacy);
                });
        assertThat(workspaceRepository.findById(workspaceId).orElseThrow().getRepositorySelection())
                .isNotEqualTo(RepositorySelection.SELECTED);
        assertThat(selectionChanges(connected)).isEmpty();
    }

    @Test
    void shouldRefuseAnAddThatReadAllProjectsWhenANarrowingCommitsWhileItWaitsForTheLock() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        long connectionId = connectionId(connected);
        long kept = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow()
                .getId();
        String target = GROUP + "/added-during-narrowing";
        long mirroredId = 2_216_090L;
        // Another workspace recorded this path; GitLab confirms the project to this one before its Add waits.
        foreignRepository(target, mirroredId);
        doReturn(Optional.of(reported(mirroredId, target)))
                .when(projectSyncService)
                .fetchProject(workspaceId, target);
        long actor = selectionAdmin("racing-narrowing-admin");
        CompletableFuture<Integer> lockedBy = new CompletableFuture<>();
        CountDownLatch narrow = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            var holder = threads.submit(() -> transactionTemplate.execute(status -> {
                connectionRepository.acquireLifecycleLock(connectionId, workspaceId);
                lockedBy.complete(
                        Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                awaitUninterruptibly(narrow);
                // Joins this transaction, so the narrowing commits only when the lock is released.
                return selectProjects(connected, RepositorySelection.SELECTED, actor);
            }));
            int holderPid = lockedBy.get(20, SECONDS);
            assertThat(holderPid).isPositive();
            Future<?> add = threads.submit(() ->
                    workspaceRepositoryMonitorService.addRepositoryToMonitor(connected.getWorkspaceSlug(), target));
            // The Add has read ALL and waits on the lifecycle lock the holder owns.
            await().atMost(Duration.ofSeconds(20))
                    .until(() -> Objects.requireNonNull(jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                                    Long.class,
                                    holderPid))
                            > 0);
            assertThat(add.isDone()).isFalse();
            narrow.countDown();

            assertThat(holder.get(20, SECONDS)).isEqualTo(RepositorySelection.SELECTED);
            assertThat(workspaceRepository
                            .findMonitorPolicy(workspaceId)
                            .orElseThrow()
                            .getRepositorySelection())
                    .isEqualTo(RepositorySelection.SELECTED);
            Throwable addOutcome = catchThrowable(() -> add.get(20, SECONDS));
            List<RepositoryToMonitor> addedAtTarget =
                    repositoryToMonitorRepository.findByWorkspaceId(workspaceId).stream()
                            .filter(monitor -> monitor.getNameWithOwner().equals(target))
                            .toList();
            String created = addedAtTarget.stream()
                    .map(monitor -> monitor.getId() + ":" + monitor.getNativeId())
                    .toList()
                    .toString();
            // The Add asked GitLab with this workspace's credential before it waited, not the shared mirror.
            verify(projectSyncService).fetchProject(workspaceId, target);

            assertThat(addOutcome)
                    .as("Add outcome; monitors created at %s as id:nativeId %s", target, created)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("changed while we checked");
            assertThat(addedAtTarget).isEmpty();
            assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, mirroredId))
                    .isEmpty();
            assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, REPOSITORY_NATIVE_ID))
                    .singleElement()
                    .satisfies(monitor -> assertThat(monitor.getId()).isEqualTo(kept));
        } finally {
            lockedBy.complete(-1);
            narrow.countDown();
            threads.shutdownNow();
        }
        awaitMonitoringIdle();
    }

    /** Changes the selection as the workspace admin's PATCH does. */
    private RepositorySelection selectProjects(Workspace target, RepositorySelection selection, long actorAccountId) {
        return gitLabRepositorySelectionService.updateGitLabRepositorySelection(
                WorkspaceContext.fromWorkspace(target, Set.of(), null), selection, actorAccountId);
    }

    private long selectionAdmin(String displayName) {
        return Objects.requireNonNull(
                accountRepository.save(new Account(displayName)).getId());
    }

    /** A monitor from before monitors carried GitLab's project id. */
    private RepositoryToMonitor pathOnlyMonitor(Workspace target, String nameWithOwner) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(nameWithOwner);
        monitor.setWorkspace(target);
        return repositoryToMonitorRepository.save(monitor);
    }

    private List<ConnectionAudit> selectionChanges(Workspace target) {
        return connectionAuditRepository.findByConnectionIdOrderByOccurredAtDesc(connectionId(target)).stream()
                .filter(audit -> audit.getEventType().equals("PROJECT_SELECTION_CHANGED"))
                .toList();
    }

    @Test
    void shouldCommitTheSelectionWithTheActiveConnectionBeforeInitializationDiscoversProjects() throws Exception {
        createStream();
        Consumer<HttpHeaders> caller = gitLabCaller("selecting-owner");
        doReturn(List.of(
                        new RepositoryIdentity(NESTED_PROJECT_ID, NESTED_PROJECT),
                        new RepositoryIdentity(REPOSITORY_NATIVE_ID, REPOSITORY)))
                .when(preflightService)
                .verify(anyString(), any(), anyString(), anyCollection());
        Repository sibling = foreignRepository(GROUP + "/unselected-at-creation", 2_216_006L);
        AtomicReference<@Nullable List<RepositoryToMonitor>> seededAtDiscovery = new AtomicReference<>();
        AtomicReference<@Nullable RepositorySelection> modeAtDiscovery = new AtomicReference<>();
        AtomicReference<@Nullable Boolean> activeAtDiscovery = new AtomicReference<>();
        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL))).thenAnswer(invocation -> {
            long scopeId = invocation.getArgument(0);
            seededAtDiscovery.set(repositoryToMonitorRepository.findByWorkspaceId(scopeId));
            modeAtDiscovery.set(workspaceRepository
                    .findMonitorPolicy(scopeId)
                    .map(policy -> policy.getRepositorySelection())
                    .orElse(null));
            activeAtDiscovery.set(connectionRepository
                    .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                            scopeId, IntegrationKind.GITLAB, IntegrationState.ACTIVE)
                    .isPresent());
            return GitLabSyncResult.completed(List.of(discoveredRepository(), sibling), 1, 0, 0);
        });

        WorkspaceDTO created = Objects.requireNonNull(webTestClient
                .post()
                .uri("/workspaces")
                .headers(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateWorkspaceRequestDTO(
                        "selected-at-creation",
                        "Selected at creation",
                        GROUP,
                        AccountType.ORG,
                        null,
                        IntegrationKind.GITLAB,
                        "glpat-test",
                        SERVER_URL,
                        RepositorySelection.SELECTED,
                        List.of(NESTED_PROJECT_ID, REPOSITORY_NATIVE_ID, NESTED_PROJECT_ID)))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(WorkspaceDTO.class)
                .returnResult()
                .getResponseBody());
        workspace = workspaceRepository.findById(created.id()).orElseThrow();
        awaitMonitoringIdle();

        ArgumentCaptor<Collection<Long>> checked = ArgumentCaptor.captor();
        verify(preflightService).verify(eq("glpat-test"), eq(SERVER_URL), eq(GROUP), checked.capture());
        assertThat(checked.getValue()).containsExactly(NESTED_PROJECT_ID, REPOSITORY_NATIVE_ID);
        // Initialization saw the committed selection, on an active connection, before it discovered anything.
        assertThat(modeAtDiscovery.get()).isEqualTo(RepositorySelection.SELECTED);
        assertThat(activeAtDiscovery.get()).isTrue();
        assertThat(seededAtDiscovery.get())
                .extracting(RepositoryToMonitor::getNativeId, RepositoryToMonitor::getNameWithOwner)
                .containsExactlyInAnyOrder(
                        tuple(NESTED_PROJECT_ID, NESTED_PROJECT), tuple(REPOSITORY_NATIVE_ID, REPOSITORY));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(created.id()))
                .extracting(RepositoryToMonitor::getNativeId)
                .containsExactlyInAnyOrder(NESTED_PROJECT_ID, REPOSITORY_NATIVE_ID);
    }

    @Test
    void shouldCreateNothingWhenGitLabDoesNotConfirmASelectedProject() throws Exception {
        createStream();
        Consumer<HttpHeaders> caller = gitLabCaller("refused-owner");
        for (HttpStatus refusal : List.of(HttpStatus.UNPROCESSABLE_CONTENT, HttpStatus.BAD_GATEWAY)) {
            String slug = "refused-" + refusal.value();
            doThrow(new ResponseStatusException(refusal, "GitLab did not confirm the selected projects."))
                    .when(preflightService)
                    .verify(anyString(), any(), anyString(), anyCollection());

            webTestClient
                    .post()
                    .uri("/workspaces")
                    .headers(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new CreateWorkspaceRequestDTO(
                            slug,
                            slug,
                            GROUP,
                            AccountType.ORG,
                            null,
                            IntegrationKind.GITLAB,
                            "glpat-test",
                            SERVER_URL,
                            RepositorySelection.SELECTED,
                            List.of(2_216_007L)))
                    .exchange()
                    .expectStatus()
                    .isEqualTo(refusal.value())
                    .expectBody(Void.class);

            assertThat(workspaceRepository.findByWorkspaceSlug(slug)).isEmpty();
        }
        awaitMonitoringIdle();
        assertThat(repositoryToMonitorRepository.findAll())
                .noneMatch(monitor -> monitor.getNativeId() != null && monitor.getNativeId() == 2_216_007L);
        verify(groupSync, never()).syncGroupProjects(anyLong(), anyString(), any());
    }

    @Test
    void shouldNotLetAReadFromBeforeAnAddAdmitTheAddedProjectWhenTheWorkspaceSelectedItsProjects() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.setRepositorySelection(RepositorySelection.SELECTED);
        workspaceRepository.save(selecting);
        GitLabProjectAdmission.ConnectionRead before =
                admission.captureConnection(workspaceId, gitLabProviderId()).orElseThrow();
        doReturn(Optional.of(reported(NESTED_PROJECT_ID, NESTED_PROJECT)))
                .when(projectSyncService)
                .fetchProject(workspaceId, NESTED_PROJECT);

        workspaceRepositoryMonitorService.addRepositoryToMonitor(connected.getWorkspaceSlug(), NESTED_PROJECT);

        // A selected workspace's Add keeps GitLab's id even with no stop or mirror to look it up.
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNameWithOwner(workspaceId, NESTED_PROJECT))
                .hasValueSatisfying(monitor -> assertThat(monitor.getNativeId()).isEqualTo(NESTED_PROJECT_ID));
        assertThat(admission.discover(before, NESTED_PROJECT_ID, NESTED_PROJECT, SERVER_URL, () -> "written"))
                .isEmpty();
        GitLabProjectAdmission.ConnectionRead after =
                admission.captureConnection(workspaceId, gitLabProviderId()).orElseThrow();
        assertThat(admission.discover(after, NESTED_PROJECT_ID, NESTED_PROJECT, SERVER_URL, () -> "written"))
                .contains("written");
    }

    @Test
    void shouldAdmitAProjectCreatedAfterTheReadWhenTheWorkspaceFollowsTheWholeGroup() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        GitLabProjectAdmission.ConnectionRead read = admission
                .captureConnection(connected.getId(), gitLabProviderId())
                .orElseThrow();

        assertThat(admission.discover(read, 2_216_004L, GROUP + "/created-later", SERVER_URL, () -> "written"))
                .contains("written");
    }

    @Test
    void shouldNotLetAnotherWorkspacesSharedSourceAdmitAProjectOutsideTheSelection() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Workspace selecting = workspaceRepository.findById(workspaceId).orElseThrow();
        selecting.setRepositorySelection(RepositorySelection.SELECTED);
        workspaceRepository.save(selecting);
        Workspace holder = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository,
                connectionRepository,
                WorkspaceTestFixtures.gitLabPatWorkspace(GROUP).withSlug("shared-source-holder"),
                SERVER_URL);
        long sharedId = 2_216_005L;
        String sharedPath = GROUP + "/shared-sibling";
        Repository shared = foreignRepository(sharedPath, sharedId);
        RepositoryToMonitor held = WorkspaceTestFixtures.repositoryMonitor(holder, sharedPath);
        held.setNativeId(sharedId);
        repositoryToMonitorRepository.save(held);
        GitLabProjectAdmission.ConnectionRead read =
                admission.captureConnection(workspaceId, gitLabProviderId()).orElseThrow();

        assertThat(admission.discover(read, sharedId, sharedPath, SERVER_URL, () -> "written"))
                .isEmpty();

        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, sharedId))
                .isEmpty();
        assertThat(repositoryToMonitorRepository.findById(held.getId())).isPresent();
        assertThat(repositoryRepository.findById(shared.getId()))
                .hasValueSatisfying(
                        repository -> assertThat(repository.getNameWithOwner()).isEqualTo(sharedPath));
    }

    /** A caller whose one GitLab identity is on the connected instance, as workspace creation requires. */
    private Consumer<HttpHeaders> gitLabCaller(String login) {
        Account account = accountRepository.save(new Account(login));
        IdentityProvider gitlab = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, SERVER_URL)
                .orElseGet(() ->
                        identityProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, SERVER_URL)));
        long nativeId = 2_216_100L + Objects.requireNonNull(account.getId());
        TestUserFactory.ensureUser(userRepository, login, nativeId, gitlab);
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(gitlab.getId()));
        link.setProviderUserKey(String.valueOf(nativeId));
        identityRepository.save(link);
        String token = "mock-jwt-sub-" + account.getId();
        return headers -> headers.setBearerAuth(token);
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
        selecting.getReviewSettings().setReviewScopeRepositories(ReviewScopeMode.SELECTED);
        workspaceRepository.save(selecting);
        practiceReviewRepositoryTargetRepository.save(new ReviewScopeRepository(workspaceId, original, List.of()));
        String renamed = GROUP + "/renamed-repository";
        doReturn(Optional.of(reported(projectId, renamed)))
                .when(projectSyncService)
                .fetchProject(workspaceId, renamed);
        doReturn(Optional.of(reported(projectId, renamed)))
                .when(projectSyncService)
                .fetchProjectById(workspaceId, projectId);

        deliver(connected, onProject(fixture("gitlab/issue.open.json"), renamed, projectId), "work-at-new-path");
        awaitAcknowledged();

        // Before the rename event: the work event alone must have moved the original monitor and
        // its review target.
        assertThat(repositoryToMonitorRepository.findByWorkspaceIdAndNativeId(workspaceId, projectId))
                .singleElement()
                .satisfies(monitor -> {
                    assertThat(monitor.getId()).isEqualTo(original);
                    assertThat(monitor.getNameWithOwner()).isEqualTo(renamed);
                });
        assertThat(issueRepository.findAll()).anyMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId()));
        Workspace reviewed = workspaceRepository.findById(workspaceId).orElseThrow();
        long repositoryItemId = itemWriter
                .upsert(new ItemRegistration(workspaceId, ScmItemEvents.REPOSITORY, project.getId(), null, null))
                .itemId();
        assertThat(practiceReviewCoverageService.scope(reviewed).repositories())
                .extracting(ReviewRepositoryTarget::repositoryItemId)
                .containsExactly(repositoryItemId);
        assertThat(practiceReviewCoverageService.admits(reviewed, repositoryItemId, "main", null, true))
                .isTrue();
        assertThat(practiceReviewCoverageService.admits(reviewed, Long.MAX_VALUE, "main", null, true))
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
        // A workspace holds one monitor per project id; a duplicate is a monitor at the path with no id yet.
        RepositoryToMonitor duplicate = WorkspaceTestFixtures.repositoryMonitor(connected, renamed);
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
                new ReviewScopeRepository(workspaceId, duplicate.getId(), List.of("main")));
        practiceReviewRepositoryTargetRepository.save(
                new ReviewScopeRepository(other.getId(), foreign.getId(), List.of("develop")));
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
    void shouldKeepADuplicateMonitorWhoseGeneratedPathsDiffer() throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        long workspaceId = connected.getId();
        Repository project = discoveredRepository();
        String renamed = GROUP + "/renamed-repository";
        RepositoryToMonitor original = repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(workspaceId, REPOSITORY)
                .orElseThrow();
        RepositoryToMonitor duplicate = WorkspaceTestFixtures.repositoryMonitor(connected, renamed);
        duplicate.setGeneratedPaths(List.of("dist/**"));
        repositoryToMonitorRepository.save(duplicate);
        project.setNameWithOwner(renamed);
        Repository listed = repositoryRepository.save(project);

        repositoryMonitors.monitorAllowed(
                workspaceRepository.findById(workspaceId).orElseThrow(), List.of(listed));

        assertThat(repositoryToMonitorRepository.findById(duplicate.getId()))
                .hasValueSatisfying(
                        monitor -> assertThat(monitor.getGeneratedPaths()).containsExactly("dist/**"));
        assertThat(repositoryToMonitorRepository.findById(original.getId())).hasValueSatisfying(monitor -> {
            assertThat(monitor.getNameWithOwner()).isEqualTo(REPOSITORY);
            assertThat(monitor.getGeneratedPaths()).isEmpty();
        });
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

        // A creator that saw the connection active waits for a disconnect holding the lock, then
        // writes nothing.
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
        doReturn(Optional.of(reported(project.getNativeId(), renamed)))
                .when(projectSyncService)
                .fetchProject(workspaceId, renamed);
        doReturn(Optional.of(reported(NESTED_PROJECT_ID, NESTED_PROJECT)))
                .when(projectSyncService)
                .fetchProject(workspaceId, NESTED_PROJECT);

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

    @Test
    void shouldRecordWhatGitLabReadForAMergeRequestOnAProjectStillMonitored() throws Exception {
        PullRequest mergeRequest = deliverMergeRequestUpdateWhileGitLabIsRead(connected -> {});

        assertThat(mergeRequest.getMergeable()).isTrue();
        assertThat(pullRequestRepository.countClosingIssuesById(mergeRequest.getId()))
                .isEqualTo(1);
    }

    @Test
    void shouldRecordNothingReadForAMergeRequestWhoseProjectStopsBeingMonitoredWhileGitLabIsRead() throws Exception {
        PullRequest mergeRequest = deliverMergeRequestUpdateWhileGitLabIsRead(connected -> repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY)
                .ifPresent(repositoryToMonitorRepository::delete));

        assertThat(mergeRequest.getMergeable()).isNull();
        assertThat(pullRequestRepository.countClosingIssuesById(mergeRequest.getId()))
                .isZero();
    }

    @Test
    void shouldRecordNothingReadForAMergeRequestWhoseProjectMovesOutOfTheGroupWhileGitLabIsRead() throws Exception {
        // Another workspace's connection records the move; this connection and its monitor are left
        // as they were.
        PullRequest mergeRequest = deliverMergeRequestUpdateWhileGitLabIsRead(
                connected -> transactionTemplate.executeWithoutResult(status -> repositoryRepository
                        .findByNameWithOwner(REPOSITORY)
                        .orElseThrow()
                        .setNameWithOwner("elsewhere/demo-repository")));

        assertThat(mergeRequest.getMergeable()).isNull();
        assertThat(pullRequestRepository.countClosingIssuesById(mergeRequest.getId()))
                .isZero();
    }

    private PullRequest deliverMergeRequestUpdateWhileGitLabIsRead(Consumer<Workspace> duringRead) throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        deliver(connected, fixture("gitlab/issue.open.json"), "closed-issue");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> issueRepository.findAll().stream()
                        .anyMatch(issue -> NATIVE_ISSUE_ID.equals(issue.getNativeId())));
        when(readinessReader.read(eq(connected.getId()), eq(REPOSITORY), eq(MERGE_REQUEST_IID)))
                .thenAnswer(invocation -> {
                    PullRequest stored = storedMergeRequest();
                    duringRead.accept(connected);
                    return new GitLabMergeRequestReadinessReader.Facts(
                            REPOSITORY_NATIVE_ID,
                            Objects.requireNonNull(stored.getNativeId()),
                            "opened",
                            Objects.requireNonNull(stored.getUpdatedAt()),
                            Objects.requireNonNull(stored.getHeadRefOid()),
                            true,
                            "mergeable",
                            null,
                            GitLabHeadPipeline.NO_PIPELINE,
                            null,
                            null,
                            GitLabMergeRequestReadinessReader.Merge.UNKNOWN,
                            null);
                });
        when(closingIssueClient.closesIssues(eq(connected.getId()), anyLong(), eq(MERGE_REQUEST_IID)))
                .thenReturn(List.of(CLOSED_ISSUE_IID));

        deliver(connected, fixture("gitlab/merge_request.update.json"), "merge-request-update");

        awaitAcknowledged();
        verify(readinessReader).read(eq(connected.getId()), eq(REPOSITORY), eq(MERGE_REQUEST_IID));
        // The connection itself stays active throughout: only the project's admission changed.
        assertThat(connectionRepository
                        .findById(connectionId(connected))
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.ACTIVE);
        return storedMergeRequest();
    }

    @Test
    void shouldRecordWhoMergedAndOfferTheMergeForReviewOnAProjectStillMonitored() throws Exception {
        PullRequest merged = deliverMergeWhileGitLabIsRead(connected -> {});

        assertThat(merged.getState()).isEqualTo(Issue.State.MERGED);
        assertThat(Objects.requireNonNull(merged.getMergedBy()).getId()).isEqualTo(userId(MEMBER_USER_ID));
        assertThat(merged.getMergedAt()).isEqualTo(MERGED_AT);
        assertThat(mergesOffered(merged)).isEqualTo(1);
    }

    @Test
    void shouldKeepTheMergeButRecordNoMergerAndOfferNoReviewWhenTheProjectStopsBeingMonitoredWhileGitLabIsRead()
            throws Exception {
        PullRequest merged = deliverMergeWhileGitLabIsRead(connected -> repositoryToMonitorRepository
                .findByWorkspaceIdAndNameWithOwner(connected.getId(), REPOSITORY)
                .ifPresent(repositoryToMonitorRepository::delete));

        assertKeptOnlyWhatTheHookSaid(merged);
    }

    @Test
    void shouldKeepTheMergeButRecordNoMergerAndOfferNoReviewWhenTheProjectMovesOutOfTheGroupWhileGitLabIsRead()
            throws Exception {
        // Another workspace's connection records the move; this connection and its monitor are left
        // as they were.
        PullRequest merged = deliverMergeWhileGitLabIsRead(
                connected -> transactionTemplate.executeWithoutResult(status -> repositoryRepository
                        .findByNameWithOwner(REPOSITORY)
                        .orElseThrow()
                        .setNameWithOwner("elsewhere/demo-repository")));

        assertKeptOnlyWhatTheHookSaid(merged);
    }

    private void assertKeptOnlyWhatTheHookSaid(PullRequest merged) {
        assertThat(merged.getState()).isEqualTo(Issue.State.MERGED);
        assertThat(merged.getMergeCommitSha()).isEqualTo(HOOK_MERGE_COMMIT);
        assertThat(merged.getMergedBy()).isNull();
        assertThat(merged.getMergedAt()).isNull();
        assertThat(scmEvents.ofType(ScmDomainEvent.PullRequestClosed.class))
                .anyMatch(closed -> closed.wasMerged()
                        && Objects.equals(closed.pullRequest().id(), merged.getId()));
        assertThat(mergesOffered(merged)).isZero();
    }

    private PullRequest deliverMergeWhileGitLabIsRead(Consumer<Workspace> duringRead) throws Exception {
        createStream();
        Workspace connected = connectGroup();
        awaitMonitoringIdle();
        deliver(connected, fixture("gitlab/merge_request.update.json"), "merge-request-opened");
        awaitAcknowledged();
        scmEvents.clear();
        when(readinessReader.read(eq(connected.getId()), eq(REPOSITORY), eq(MERGE_REQUEST_IID)))
                .thenAnswer(invocation -> {
                    PullRequest stored = storedMergeRequest();
                    duringRead.accept(connected);
                    return new GitLabMergeRequestReadinessReader.Facts(
                            REPOSITORY_NATIVE_ID,
                            Objects.requireNonNull(stored.getNativeId()),
                            "merged",
                            Objects.requireNonNull(stored.getUpdatedAt()),
                            Objects.requireNonNull(stored.getHeadRefOid()),
                            null,
                            null,
                            null,
                            GitLabHeadPipeline.NO_PIPELINE,
                            null,
                            null,
                            new GitLabMergeRequestReadinessReader.Merge(
                                    new GitLabMergeRequestProcessor.SyncUserData(
                                            "gid://gitlab/User/" + MEMBER_USER_ID,
                                            "ab12cde",
                                            "Muster, Max (Erika)",
                                            null,
                                            SERVER_URL + "/ab12cde",
                                            null,
                                            null),
                                    MERGED_AT,
                                    HOOK_MERGE_COMMIT),
                            null);
                });

        deliver(connected, fixture("gitlab/merge_request.merge.json"), "merge-request-merged");

        awaitAcknowledged();
        verify(readinessReader, times(2)).read(eq(connected.getId()), eq(REPOSITORY), eq(MERGE_REQUEST_IID));
        assertThat(connectionRepository
                        .findById(connectionId(connected))
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.ACTIVE);
        return storedMergeRequest();
    }

    private long mergesOffered(PullRequest mergeRequest) {
        return scmEvents.ofType(ScmDomainEvent.PullRequestMerged.class).stream()
                .filter(offered -> Objects.equals(offered.pullRequest().id(), mergeRequest.getId()))
                .count();
    }

    private Long userId(long nativeId) {
        return userRepository
                .findByNativeIdAndProviderId(nativeId, gitLabProviderId())
                .orElseThrow()
                .getId();
    }

    private PullRequest storedMergeRequest() {
        Repository repository = repositoryRepository
                .findByNativeIdAndProviderId(REPOSITORY_NATIVE_ID, gitLabProviderId())
                .orElseThrow();
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), MERGE_REQUEST_IID)
                .orElseThrow();
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

    /** Delivers {@code payload} on a route credential issued for {@code target}'s connection and group. */
    private void deliverOnRoute(Workspace target, JsonNode payload, String deliveryKey) throws Exception {
        long connectionId = connectionId(target);
        GitLabRouteCredential.Issued issued = routeCredential.issue(
                new GitLabRouteCredential.Route(connectionId, target.getId(), SERVER_URL, GROUP_ID, GROUP));
        assertThat(ingest(connectionId, routeOf(issued), issued.token(), SERVER_URL, deliveryKey, bytes(payload)))
                .isEqualTo(HttpStatus.ACCEPTED);
    }

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
        String[] segments = route.split("/", -1);
        return Objects.requireNonNull(ingress)
                .ingest(connectionId, segments[0], segments[1], request)
                .getStatusCode();
    }

    private static String routeOf(GitLabWebhookClient.WebhookConfig hook) {
        String[] segments = hook.url().split("/", -1);
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
                        "gid://gitlab/User/" + MEMBER_USER_ID, "ab12cde", "Muster, Max (Erika)", null, null, null),
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
                new ConnectionConfig.GitLabConfig(SERVER_URL, null, null, Set.of(), null),
                "glpat-test",
                "event-routing-" + saved.getId());
        return saved;
    }

    /** A GitLab workspace connected by an earlier run, with its group known. */
    private Workspace persistedWorkspace() {
        Workspace saved = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaceRepository, connectionRepository, WorkspaceTestFixtures.gitLabPatWorkspace(GROUP), SERVER_URL);
        workspace = saved;
        connectionService.rotateBearerToken(saved.getId(), IntegrationKind.GITLAB, new BearerToken("glpat-test", null));
        connectionService.updateConfig(
                saved.getId(),
                IntegrationKind.GITLAB,
                config -> ((ConnectionConfig.GitLabConfig) config).withGitlabGroupId(GROUP_ID));
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
            repository.setNativeId(REPOSITORY_NATIVE_ID);
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

    private Set<String> expectedFilter(Workspace target) {
        return Set.of(ConsumerSubjectMath.connectionFilter(STREAM, connectionId(target)));
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
        // A receiver that an earlier test class booted leaves a stream of this name with its own configuration.
        if (jsm.getStreamNames().contains(STREAM)) {
            jsm.deleteStream(STREAM);
        }
        jsm.addStream(StreamConfiguration.builder()
                .name(STREAM)
                .subjects(STREAM + ".>")
                .storageType(StorageType.Memory)
                .build());
    }
}
