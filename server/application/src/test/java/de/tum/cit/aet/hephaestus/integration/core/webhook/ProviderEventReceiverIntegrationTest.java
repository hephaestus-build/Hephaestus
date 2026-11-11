package de.tum.cit.aet.hephaestus.integration.core.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private ConnectionRepository connections;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldStoreTheEventForEachWorkspaceAndDedupeARedeliveryInEachWhenTwoWorkspacesMonitorTheRepository() {
        Workspace first = workspace("fanout-a", 20001L);
        Workspace second = workspace("fanout-b", 20002L);

        assertThat(receiver.receive(
                        IntegrationKind.GITHUB,
                        SUBJECT,
                        "github-d-1",
                        Map.of(ProviderEvent.SOURCE_SERVER_URL, "https://untrusted.example.test"),
                        BODY))
                .isEqualTo(2);
        assertThat(receiver.receive(IntegrationKind.GITHUB, SUBJECT, "github-d-1", Map.of(), BODY))
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
