package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuetype.GitLabIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabGroupSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.GitLabSyncResult;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitlabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.NatsTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActivationService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

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
        when(gitLabTokenRotationClient.getTokenInfo(anyLong()))
                .thenReturn(new GitLabTokenRotationClient.TokenInfo(1L, "hephaestus", null));
        when(gitLabWebhookClient.lookupGroup(anyLong(), eq(GROUP)))
                .thenReturn(new GitLabWebhookClient.GroupInfo(GROUP_ID, "HephaestusTest", GROUP));
        when(gitLabWebhookClient.registerGroupWebhook(anyLong(), eq(GROUP_ID), any()))
                .thenAnswer(invocation -> {
                    filterWhenRegistered.set(filterSubjects());
                    return new GitLabWebhookClient.WebhookInfo(WEBHOOK_ID, WEBHOOK_URL);
                });
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
                    filterWhenRegistered.set(filterSubjects());
                    publish(ISSUE_SUBJECT, "gitlab/issue.open.json");
                    publish(NOTE_SUBJECT, "gitlab/note.issue.create.json");
                    return new GitLabWebhookClient.WebhookInfo(WEBHOOK_ID, WEBHOOK_URL);
                });
        holdFullSync();

        Workspace connected = connectGroup();

        assertThat(syncBlocked.await(30, SECONDS)).isTrue();
        assertThat(filterWhenRegistered.get()).isEqualTo(expectedFilter());
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
                        .filterSubjects(expectedFilter().toArray(String[]::new))
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
        assertThat(filterSubjects()).isEqualTo(expectedFilter());
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
        assertThat(filterWhenRegistered.get()).isEqualTo(expectedFilter());
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
        assertThat(filterSubjects()).isEqualTo(expectedFilter());
        verify(gitLabWebhookClient, never()).registerGroupWebhook(anyLong(), anyLong(), any());

        when(groupSync.syncGroupProjects(anyLong(), eq(GROUP), eq(SERVER_URL)))
                .thenAnswer(invocation -> GitLabSyncResult.completed(List.of(discoveredRepository()), 1, 0, 0));
        reconcile(connected);

        assertThat(webhookId(connected)).isEqualTo(WEBHOOK_ID);
        verify(gitLabWebhookClient, times(1)).registerGroupWebhook(anyLong(), anyLong(), any());
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
                        .withGitlabWebhookId(WEBHOOK_ID));
        when(gitLabWebhookClient.getGroupWebhook(anyLong(), eq(GROUP_ID), eq(WEBHOOK_ID)))
                .thenReturn(Optional.of(new GitLabWebhookClient.WebhookInfo(WEBHOOK_ID, WEBHOOK_URL)));
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
            organization.setNativeId(1L);
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

    private static Set<String> expectedFilter() {
        return Set.of(
                ConsumerSubjectMath.repositoryFilter(STREAM, REPOSITORY),
                ConsumerSubjectMath.organizationFilter(STREAM, GROUP));
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
