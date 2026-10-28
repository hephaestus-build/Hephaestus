package de.tum.cit.aet.hephaestus.integration.scm.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.tum.cit.aet.hephaestus.activity.spi.ActivityLedgerRepair;
import de.tum.cit.aet.hephaestus.core.runtime.ServerSchedulingConfig;
import de.tum.cit.aet.hephaestus.core.runtime.ShedLockConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.framework.SyncSchedulerProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncContextProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncSession;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncStatistics;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.check.GitHubHeadCheckRunsReread;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.RateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.GitHubIssueDependencySyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuetype.GitHubIssueTypeSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.organization.GitHubOrganizationSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.project.GitHubProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.subissue.GitHubSubIssueSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDataSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.github.sync.GitHubDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.github.team.GitHubTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncServiceHolder;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.GitLabProjectSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDataSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync.GitLabDeletionSweepService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRepositoryMonitors;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.web.server.GracefulShutdownCallback;
import org.springframework.boot.web.server.GracefulShutdownResult;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shutting down a server that runs a scheduled SCM sync.
 *
 * <p>Each test boots a server context with the production scheduling and lock configuration and the
 * real {@code application.yml}: Boot's task scheduler fires the cron method, the ShedLock advice
 * takes the lock through its own {@link HikariDataSource} and a JPA {@code entityManagerFactory}.
 * Closing that context is the shutdown. The context's DataSource records what the database showed
 * at the moment it was closed. A second {@link JdbcTemplateLockProvider} plays another replica, and
 * the sync-job rows are real. Only provider calls are controlled: the first provider call of a
 * workspace sync waits at a gate. Where a test needs the web drain, Boot's own graceful-shutdown
 * lifecycle runs over a web server whose drain stays open until the test releases it, as an open
 * live-update stream keeps Tomcat's drain open.
 */
class ScheduledScmSyncShutdownIntegrationTest extends AbstractWorkspaceIntegrationTest {

    enum Provider {
        GITHUB,
        GITLAB
    }

    /** What the database showed when the server closed its DataSource. */
    private record AtDatabaseClose(boolean lockReleased, SyncJobStatus jobStatus, boolean scheduledSyncRunning) {}

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private SyncJobRepository jobRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionTemplate transactions;

    private final CountDownLatch gateReached = new CountDownLatch(1);
    private final CountDownLatch gate = new CountDownLatch(1);
    private final ExecutorService workspaceSyncs = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletableFuture<AtDatabaseClose> atDatabaseClose = new CompletableFuture<>();
    private final AtomicReference<@Nullable Thread> scheduledSync = new AtomicReference<>();
    private SyncJobService jobs;
    private LockProvider otherReplica;
    private String lockName = "";
    private long connectionId;
    private @Nullable AnnotationConfigApplicationContext server;
    private @Nullable WebServer webServer;

    @BeforeEach
    void setUp() {
        // The server context stops this instance on close; the shared test context keeps its own.
        jobs = new SyncJobService(jobRepository, connectionRepository, workspaceRepository, events, transactions);
        otherReplica = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbc)
                .usingDbTime()
                .build());
        freeLocks();
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        gate.countDown();
        AnnotationConfigApplicationContext context = server;
        if (context != null) {
            context.close();
        }
        Thread run = scheduledSync.get();
        if (run != null) {
            run.join(Duration.ofSeconds(30));
        }
        workspaceSyncs.close();
        freeLocks();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldRecordOutcomeAndReleaseLockBeforeTheDatabaseClosesWhenShutdownStopsAScheduledSync(Provider provider)
            throws Exception {
        startServer(provider, List.of(connectedSession(provider)), workspaceSyncs, Duration.ofSeconds(30));
        List<Set<String>> schedulerDependencies = schedulerDependencies();
        awaitWorkspaceSyncAtGate();

        Thread shutdown = Thread.ofPlatform().start(() -> requireServer().close());
        awaitCancellationRequested();
        gate.countDown();

        assertThat(shutdown.join(Duration.ofSeconds(30))).isTrue();
        AtDatabaseClose seen = atDatabaseClose.get(30, TimeUnit.SECONDS);
        assertThat(seen.jobStatus())
                .as("the workspace sync recorded its own outcome before the database closed")
                .isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(seen.lockReleased())
                .as("the scheduled sync released its lock before the database closed")
                .isTrue();
        assertThat(schedulerDependencies)
                .as("every task scheduler closes before the database beans")
                .isNotEmpty()
                .allSatisfy(dependencies -> assertThat(dependencies).contains("entityManagerFactory", "dataSource"));
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldRequestCancellationBeforeTheWebDrainWhenShutdownStopsAScheduledSync(Provider provider) throws Exception {
        HeldDrainWebServer web = new HeldDrainWebServer();
        webServer = web;
        startServer(provider, List.of(connectedSession(provider)), workspaceSyncs, Duration.ofSeconds(30));
        awaitWorkspaceSyncAtGate();

        Thread shutdown = Thread.ofPlatform().start(() -> requireServer().close());
        try {
            assertThat(web.drainBegan.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(web.cancelRequestedWhenDrainBegan)
                    .as("the in-flight sync was asked to cancel before the web drain began")
                    .isTrue();
            gate.countDown();
            // The drain is still held here, so the sync records its outcome while requests drain.
            await().atMost(Duration.ofSeconds(30)).until(() -> latestJob().getStatus() == SyncJobStatus.CANCELLED);
        } finally {
            web.releaseDrain.countDown();
            gate.countDown();
        }

        assertThat(shutdown.join(Duration.ofSeconds(30))).isTrue();
        AtDatabaseClose seen = atDatabaseClose.get(30, TimeUnit.SECONDS);
        assertThat(seen.jobStatus()).isEqualTo(SyncJobStatus.CANCELLED);
        assertThat(seen.lockReleased())
                .as("the scheduled sync released its lock before the database closed")
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldKeepLockAndRunningJobWhenTheWorkspaceSyncOutlastsTheShutdownWait(Provider provider) throws Exception {
        startServer(provider, List.of(connectedSession(provider)), workspaceSyncs, Duration.ofSeconds(1));
        awaitWorkspaceSyncAtGate();

        Thread shutdown = Thread.ofPlatform().start(() -> requireServer().close());

        assertThat(shutdown.join(Duration.ofSeconds(30))).isTrue();
        AtDatabaseClose seen = atDatabaseClose.get(30, TimeUnit.SECONDS);
        assertThat(seen.scheduledSyncRunning())
                .as("the interrupted scheduled sync still waits for its workspace sync")
                .isTrue();
        assertThat(seen.lockReleased()).isFalse();
        assertThat(seen.jobStatus()).isEqualTo(SyncJobStatus.RUNNING);

        gate.countDown();

        Thread run = Objects.requireNonNull(scheduledSync.get());
        assertThat(run.join(Duration.ofSeconds(30))).isTrue();
        assertThat(otherReplicaAcquires())
                .as("with its database gone, the lock is left to expire")
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void shouldKeepLockForTheAcceptedWorkspaceSyncWhenTheExecutorRejectsTheNextOne(Provider provider) throws Exception {
        SyncSession accepted = connectedSession(provider);
        long refusedScope = accepted.scopeId() + 1_000_000;
        SyncSession refused = new SyncSession(
                refusedScope,
                "refused",
                "Refused",
                accepted.accountLogin(),
                null,
                null,
                List.of(),
                new SyncContextProvider.SyncContext(refusedScope, "refused", "Refused", null));
        AtomicInteger submissions = new AtomicInteger();
        CountDownLatch rejectNow = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(1);
        Executor acceptsOne = task -> {
            if (submissions.getAndIncrement() == 0) {
                workspaceSyncs.execute(task);
                return;
            }
            awaitLatch(rejectNow);
            rejected.countDown();
            throw new RejectedExecutionException("The executor is closed");
        };
        startServer(provider, List.of(accepted, refused), acceptsOne, Duration.ofSeconds(1));
        awaitWorkspaceSyncAtGate();
        rejectNow.countDown();
        assertThat(rejected.await(10, TimeUnit.SECONDS)).isTrue();

        Thread shutdown = Thread.ofPlatform().start(() -> requireServer().close());

        assertThat(shutdown.join(Duration.ofSeconds(30))).isTrue();
        AtDatabaseClose seen = atDatabaseClose.get(30, TimeUnit.SECONDS);
        assertThat(seen.scheduledSyncRunning())
                .as("the rejection waits for the accepted workspace sync")
                .isTrue();
        assertThat(seen.lockReleased()).isFalse();
        assertThat(seen.jobStatus()).isEqualTo(SyncJobStatus.RUNNING);
        assertThat(submissions).hasValue(2);
    }

    private void startServer(Provider provider, List<SyncSession> sessions, Executor executor, Duration shutdownWait) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(
                        "spring.profiles.active=default",
                        "hephaestus.sync.cron=* * * * * *",
                        "hephaestus.integration.gitlab.enabled=true",
                        // await-termination itself comes from application.yml.
                        "spring.task.scheduling.shutdown.await-termination-period=" + shutdownWait.toMillis() + "ms")
                .applyTo(context);
        new ConfigDataApplicationContextInitializer().initialize(context);
        context.registerBean("dataSource", HikariDataSource.class, this::serverDataSource);
        context.registerBean(
                "entityManagerFactory",
                LocalContainerEntityManagerFactoryBean.class,
                () -> entityManagerFactory(context.getBean(DataSource.class)),
                definition -> definition.setDependsOn("dataSource"));
        context.register(
                AopAutoConfiguration.class,
                ShedLockConfig.class,
                ServerSchedulingConfig.class,
                TaskSchedulingAutoConfiguration.class);
        context.registerBean("syncJobService", SyncJobService.class, () -> jobs);
        WebServer web = webServer;
        if (web != null) {
            context.registerBean(
                    "webServerGracefulShutdown",
                    WebServerGracefulShutdownLifecycle.class,
                    () -> new WebServerGracefulShutdownLifecycle(web));
        }
        if (provider == Provider.GITHUB) {
            lockName = lockName(GitHubDataSyncScheduler.class);
            context.registerBean(GitHubDataSyncScheduler.class, () -> gitHubScheduler(sessions, executor));
        } else {
            lockName = lockName(GitLabDataSyncScheduler.class);
            context.registerBean(GitLabDataSyncScheduler.class, () -> gitLabScheduler(sessions, executor));
        }
        server = context;
        context.refresh();
    }

    private List<Set<String>> schedulerDependencies() {
        var beans = requireServer().getBeanFactory();
        return Arrays.stream(beans.getBeanNamesForType(TaskScheduler.class))
                .map(name -> Set.of(
                        Objects.requireNonNullElse(beans.getBeanDefinition(name).getDependsOn(), new String[0])))
                .toList();
    }

    private HikariDataSource serverDataSource() {
        var postgres = PostgreSQLTestContainer.getInstance();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        config.setPoolName("scheduled-shutdown");
        return new HikariDataSource(config) {
            @Override
            public void close() {
                if (!isClosed()) {
                    try {
                        Thread run = scheduledSync.get();
                        atDatabaseClose.complete(new AtDatabaseClose(
                                otherReplicaAcquires(), latestJob().getStatus(), run != null && run.isAlive()));
                    } catch (RuntimeException e) {
                        atDatabaseClose.completeExceptionally(e);
                    }
                }
                super.close();
            }
        };
    }

    private static LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPersistenceUnitName("scheduled-shutdown");
        // A package without entities: only the factory's lifecycle matters here.
        factory.setPackagesToScan(ScheduledScmSyncShutdownIntegrationTest.class.getPackageName());
        return factory;
    }

    private void awaitWorkspaceSyncAtGate() throws InterruptedException {
        assertThat(gateReached.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(otherReplicaAcquires())
                .as("the scheduled sync holds its lock")
                .isFalse();
        // Model a sync that is older than its one-minute lock floor, so a release frees the row at once.
        assertThat(jdbc.update(
                        "UPDATE shedlock SET locked_at = locked_at - interval '2 minutes' WHERE name = ?", lockName))
                .isEqualTo(1);
    }

    private void awaitCancellationRequested() {
        await().atMost(Duration.ofSeconds(30)).until(() -> latestJob().isCancelRequested());
    }

    private SyncSession connectedSession(Provider provider) {
        Workspace workspace = createWorkspace(
                "scheduled-shutdown-" + provider.name().toLowerCase(Locale.ROOT),
                "Scheduled shutdown",
                "scheduled-group",
                AccountType.ORG,
                persistUser("scheduled-owner"));
        connectionId = connectionRepository
                .saveAndFlush(activeConnection(workspace, provider))
                .getId();
        return new SyncSession(
                workspace.getId(),
                workspace.getWorkspaceSlug(),
                workspace.getDisplayName(),
                workspace.getAccountLogin(),
                null,
                null,
                List.of(),
                new SyncContextProvider.SyncContext(
                        workspace.getId(), workspace.getWorkspaceSlug(), workspace.getDisplayName(), null));
    }

    private GitHubDataSyncScheduler gitHubScheduler(List<SyncSession> sessions, Executor executor) {
        SyncTargetProvider targets = mock(SyncTargetProvider.class);
        when(targets.getSyncStatistics()).thenReturn(new SyncStatistics(1, 0, 0, 1, false));
        when(targets.getSyncSessions(IntegrationKind.GITHUB)).thenAnswer(ignored -> {
            scheduledSync.set(Thread.currentThread());
            return sessions;
        });
        GitHubIssueTypeSyncService issueTypes = mock(GitHubIssueTypeSyncService.class);
        when(issueTypes.syncIssueTypesForScope(sessions.getFirst().scopeId())).thenAnswer(ignored -> {
            passGate();
            return 0;
        });
        return new GitHubDataSyncScheduler(
                targets,
                contextProvider(),
                mock(GitHubDataSyncService.class),
                mock(GitHubDeletionSweepService.class),
                mock(GitHubSubIssueSyncService.class),
                issueTypes,
                mock(GitHubIssueDependencySyncService.class),
                mock(GitHubProjectSyncService.class),
                mock(GitHubOrganizationSyncService.class),
                mock(GitHubTeamSyncService.class),
                mock(OrganizationRepository.class),
                properties(),
                mock(RateLimitTracker.class),
                executor,
                connectionRepository,
                jobs,
                mock(GitHubHeadCheckRunsReread.class));
    }

    private GitLabDataSyncScheduler gitLabScheduler(List<SyncSession> sessions, Executor executor) {
        SyncTargetProvider targets = mock(SyncTargetProvider.class);
        when(targets.getSyncSessions(IntegrationKind.GITLAB)).thenAnswer(ignored -> {
            scheduledSync.set(Thread.currentThread());
            return sessions;
        });
        GitLabWorkspaceInitializationService initialization = mock(GitLabWorkspaceInitializationService.class);
        when(initialization.initializeIfWebhookMissing(sessions.getFirst().scopeId()))
                .thenAnswer(ignored -> {
                    passGate();
                    return false;
                });
        return new GitLabDataSyncScheduler(
                targets,
                contextProvider(),
                mock(OrganizationRepository.class),
                mock(RepositoryRepository.class),
                providerOf(mock(GitLabSyncServiceHolder.class)),
                providerOf(mock(GitLabRateLimitTracker.class)),
                properties(),
                executor,
                connectionRepository,
                jobs,
                mock(GitLabDeletionSweepService.class),
                initialization,
                mock(GitLabRepositoryMonitors.class),
                workspaceRepository,
                mock(WorkspaceActorSelector.class),
                mock(GitLabProjectSyncService.class),
                mock(ActivityLedgerRepair.class),
                mock(GitLabProjectAdmission.class));
    }

    /** Records whether cancellation was already requested when the drain began, then holds the drain. */
    private final class HeldDrainWebServer implements WebServer {
        private final CountDownLatch drainBegan = new CountDownLatch(1);
        private final CountDownLatch releaseDrain = new CountDownLatch(1);
        private volatile boolean cancelRequestedWhenDrainBegan;

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public int getPort() {
            return -1;
        }

        /** Returns at once and completes later, as Tomcat's graceful shutdown does. */
        @Override
        public void shutDownGracefully(GracefulShutdownCallback callback) {
            cancelRequestedWhenDrainBegan = latestJob().isCancelRequested();
            drainBegan.countDown();
            Thread.ofPlatform().start(() -> {
                awaitLatch(releaseDrain);
                callback.shutdownComplete(GracefulShutdownResult.IDLE);
            });
        }
    }

    private AnnotationConfigApplicationContext requireServer() {
        return Objects.requireNonNull(server);
    }

    private void passGate() {
        gateReached.countDown();
        awaitLatch(gate);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private boolean otherReplicaAcquires() {
        Optional<SimpleLock> lock =
                otherReplica.lock(new LockConfiguration(Instant.now(), lockName, Duration.ofMinutes(1), Duration.ZERO));
        lock.ifPresent(SimpleLock::unlock);
        return lock.isPresent();
    }

    private SyncJob latestJob() {
        return jobRepository
                .findFirstByConnection_IdAndStatusInOrderByCreatedAtDesc(
                        connectionId, EnumSet.allOf(SyncJobStatus.class))
                .orElseThrow();
    }

    /** Frees the rows instead of deleting them: a lock provider remembers the rows it created. */
    private void freeLocks() {
        for (Class<?> scheduler : List.of(GitHubDataSyncScheduler.class, GitLabDataSyncScheduler.class)) {
            jdbc.update("UPDATE shedlock SET lock_until = locked_at WHERE name = ?", lockName(scheduler));
        }
    }

    private static String lockName(Class<?> scheduler) {
        try {
            return scheduler
                    .getMethod("syncDataCron")
                    .getAnnotation(SchedulerLock.class)
                    .name();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked") // mock() cannot carry the type argument of ObjectProvider
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static Connection activeConnection(Workspace workspace, Provider provider) {
        Connection connection = provider == Provider.GITHUB
                ? new Connection(
                        workspace,
                        IntegrationKind.GITHUB,
                        workspace.getAccountLogin(),
                        new ConnectionConfig.GitHubPatConfig(workspace.getAccountLogin(), null, Set.of()))
                : new Connection(
                        workspace,
                        IntegrationKind.GITLAB,
                        workspace.getAccountLogin(),
                        new ConnectionConfig.GitLabConfig(
                                "https://gitlab.com",
                                null,
                                null,
                                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                                Set.of(),
                                null));
        connection.setState(IntegrationState.ACTIVE);
        return connection;
    }

    private static SyncContextProvider contextProvider() {
        SyncContextProvider context = mock(SyncContextProvider.class);
        when(context.wrapWithContext(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return context;
    }

    private static SyncSchedulerProperties properties() {
        return new SyncSchedulerProperties(
                true,
                7,
                "0 0 3 * * *",
                15,
                new SyncSchedulerProperties.BackfillProperties(false, 50, 100, 60),
                new SyncSchedulerProperties.FilterProperties(Set.of(), Set.of(), Set.of()),
                new SyncSchedulerProperties.DiscussionsProperties(false),
                new SyncSchedulerProperties.ProjectsProperties(false));
    }
}
