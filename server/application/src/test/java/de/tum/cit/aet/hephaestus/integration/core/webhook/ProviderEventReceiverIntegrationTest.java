package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The receive path against PostgreSQL: its fan-out, its dedupe, and its bound on a blocked database. */
class ProviderEventReceiverIntegrationTest extends BaseIntegrationTest {

    private static final String LOGIN = "FanOutOrg";
    private static final String SUBJECT = "github." + LOGIN + ".app.issues";
    private static final byte[] BODY = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private ProviderEventReceiver receiver;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldStoreTheEventForEachWorkspaceAndDedupeARedeliveryInEachWhenTwoWorkspacesMonitorTheRepository() {
        Workspace first = workspace("fanout-a", 20001L);
        Workspace second = patWorkspace("fanout-b", 20002L, "https://github.com");

        assertThat(receiver.receive(
                        IntegrationKind.GITHUB,
                        SUBJECT,
                        "github-d-1",
                        Map.of(
                                ProviderEvent.SOURCE_SERVER_URL,
                                "https://untrusted.example.test",
                                ProviderEvent.GITHUB_INSTALLATION_ID,
                                "20001"),
                        BODY))
                .isEqualTo(2);
        assertThat(receiver.receive(
                        IntegrationKind.GITHUB,
                        SUBJECT,
                        "github-d-1",
                        Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20001"),
                        BODY))
                .as("GitHub keeps X-GitHub-Delivery on a redelivery")
                .isZero();

        assertThat(jdbc.queryForList(
                        "SELECT workspace_id FROM provider_event WHERE delivery_key = 'github-d-1'", Long.class))
                .containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(jdbc.queryForList(
                        "SELECT headers->>? FROM provider_event WHERE delivery_key = ?",
                        String.class,
                        ProviderEvent.SOURCE_SERVER_URL,
                        "github-d-1"))
                .containsExactlyInAnyOrder("https://github.com", "https://github.com");
    }

    @Test
    void shouldFailWithinTheDeadlineWhenTheInboxIsBlocked() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LOCK TABLE provider_event IN ACCESS EXCLUSIVE MODE");
                }
                locked.countDown();
                release.await(30, TimeUnit.SECONDS);
                connection.rollback();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long start = System.nanoTime();

            assertThatThrownBy(() -> receiver.receive(
                            IntegrationKind.GITHUB, "github.?.?.installation", "github-blocked", Map.of(), BODY))
                    .isInstanceOf(DataAccessException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("inside Slack's 3-second response window")
                    .isLessThan(Duration.ofMillis(2800));
        } finally {
            release.countDown();
            holder.join(Duration.ofSeconds(10));
        }
    }

    @Test
    void shouldRejectAnEventWhoseConnectionIsSuspendedWhileAdmissionWaitsOnItsLifecycleLock() throws Exception {
        Workspace workspace = workspace("admission-race", 20003L);
        var connection = connections
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        long connectionId = Objects.requireNonNull(connection.getId());
        HeldTransaction suspending = HeldTransaction.holdIn(transactions, jdbc, () -> {
            connections.acquireLifecycleLock(connectionId, workspace.getId());
            var current = connections
                    .findByIdAndWorkspaceId(connectionId, workspace.getId())
                    .orElseThrow();
            current.setState(IntegrationState.SUSPENDED);
            connections.saveAndFlush(current);
        });
        var receiving = CompletableFuture.supplyAsync(() -> receiver.receive(
                IntegrationKind.GITHUB,
                SUBJECT,
                "suspension-race",
                Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20003"),
                BODY));
        suspending.commitAfter(receiving);

        assertThat(receiving.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, workspace.getId()))
                .isZero();
    }

    @Test
    void shouldNotPersistAWorkEventInTheSystemScopeWhenItsRepositoryIsMissing() {
        assertThat(receiver.receive(IntegrationKind.GITHUB, "github.?.?.issues", "missing-work-source", Map.of(), BODY))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE delivery_key = ?",
                        Integer.class,
                        "missing-work-source"))
                .isZero();
    }

    @Test
    void shouldNotFanOutASuspendedInstallationsEventToAnActivePatWorkspace() {
        Workspace source = workspace("inactive-source", 20004L);
        Workspace recipient = patWorkspace("pat-recipient", 20005L, "https://github.com");
        transactions.executeWithoutResult(status -> {
            var connection = connections
                    .findActive(source.getId(), IntegrationKind.GITHUB)
                    .orElseThrow();
            connections.acquireLifecycleLock(Objects.requireNonNull(connection.getId()), source.getId());
            connection.setState(IntegrationState.SUSPENDED);
            connections.saveAndFlush(connection);
        });

        assertThat(receiver.receive(
                        IntegrationKind.GITHUB,
                        SUBJECT,
                        "inactive-app-fanout",
                        Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20004"),
                        BODY))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id IN (?, ?)",
                        Integer.class,
                        source.getId(),
                        recipient.getId()))
                .isZero();
    }

    @Test
    void shouldNotRouteAGithubComEventToAnotherServersSameNamedPatRepository() {
        workspace("github-source", 20006L);
        Workspace foreign = patWorkspace("foreign-pat", 20007L, "https://github.other.test");

        assertThat(receiver.receive(
                        IntegrationKind.GITHUB,
                        SUBJECT,
                        "github-origin-fence",
                        Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20006"),
                        BODY))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, foreign.getId()))
                .isZero();
    }

    @Test
    void shouldRejectWorkWithoutAnInstallationBeforePatFanout() {
        Workspace recipient = patWorkspace("missing-installation", 20008L, "https://github.com");
        var refused = meters.counter(
                IntegrationCoreMetrics.WEBHOOK_REJECTED, "provider", "github", "reason", "work-event-without-source");
        double before = refused.count();

        assertThat(receiver.receive(IntegrationKind.GITHUB, SUBJECT, "missing-installation", Map.of(), BODY))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id = ?", Integer.class, recipient.getId()))
                .isZero();
        assertThat(refused.count()).isEqualTo(before + 1);
    }

    @Test
    void shouldSkipAConnectionDeletedWhileAdmissionWaitsWithoutLosingOtherWorkspaces() throws Exception {
        Workspace source = workspace("delete-recipient-source", 20009L);
        Workspace recipient = patWorkspace("deleted-recipient", 20010L, "https://github.com");
        var connection = connections
                .findActive(recipient.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        long id = Objects.requireNonNull(connection.getId());
        HeldTransaction deleting = HeldTransaction.holdIn(transactions, jdbc, () -> {
            connections.acquireLifecycleLock(id, recipient.getId());
            jdbc.update("DELETE FROM connection WHERE id = ? AND workspace_id = ?", id, recipient.getId());
        });
        var receiving = CompletableFuture.supplyAsync(() -> receiver.receive(
                IntegrationKind.GITHUB,
                SUBJECT,
                "deleted-recipient",
                Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20009"),
                BODY));
        deleting.commitAfter(receiving);

        assertThat(receiving.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(jdbc.queryForList(
                        "SELECT workspace_id FROM provider_event WHERE delivery_key = ?",
                        Long.class,
                        "deleted-recipient"))
                .containsExactly(source.getId());
        assertThatThrownBy(
                        () -> transactions.execute(status -> connections.acquireLifecycleLock(id, recipient.getId())))
                .as("Existing lifecycle callers still require a present row")
                .isInstanceOf(EmptyResultDataAccessException.class);
    }

    @Test
    void shouldRejectFanoutWhenTheSourceInstallationIsDeletedWhileAdmissionWaits() throws Exception {
        Workspace source = workspace("deleted-app-source", 20011L);
        Workspace recipient = patWorkspace("deleted-app-pat", 20012L, "https://github.com");
        var connection =
                connections.findActive(source.getId(), IntegrationKind.GITHUB).orElseThrow();
        long id = Objects.requireNonNull(connection.getId());
        HeldTransaction deleting = HeldTransaction.holdIn(transactions, jdbc, () -> {
            connections.acquireLifecycleLock(id, source.getId());
            jdbc.update("DELETE FROM connection WHERE id = ? AND workspace_id = ?", id, source.getId());
        });
        var receiving = CompletableFuture.supplyAsync(() -> receiver.receive(
                IntegrationKind.GITHUB,
                SUBJECT,
                "deleted-app-source",
                Map.of(ProviderEvent.GITHUB_INSTALLATION_ID, "20011"),
                BODY));
        deleting.commitAfter(receiving);

        assertThat(receiving.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE workspace_id IN (?, ?)",
                        Integer.class,
                        source.getId(),
                        recipient.getId()))
                .isZero();
    }

    private Workspace patWorkspace(String slug, long nativeId, String serverUrl) {
        Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace(slug));
        var config = new ConnectionConfig.GitHubPatConfig(LOGIN, serverUrl, Set.of());
        var connection = new de.tum.cit.aet.hephaestus.integration.core.connection.Connection(
                workspace, IntegrationKind.GITHUB, "pat", config);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        monitors.saveAndFlush(WorkspaceTestFixtures.repositoryMonitor(workspace, LOGIN + "/app", nativeId));
        return workspace;
    }

    private Workspace workspace(String slug, long installation) {
        Workspace workspace = WorkspaceTestFixtures.persistInstallationWorkspace(
                workspaces,
                connections,
                WorkspaceTestFixtures.installationWorkspace(installation, LOGIN).withSlug(slug),
                installation);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNativeId(installation);
        monitor.setNameWithOwner(LOGIN + "/app");
        monitors.save(monitor);
        return workspace;
    }
}
