package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Admin disconnect of a GitHub connection through the real endpoint, strategy and SCM eraser, on
 * PostgreSQL. No GitHub App credentials are configured in tests, so the provider teardown always fails;
 * a trigger on {@code repository_to_monitor} makes the local erase fail with a genuine SQL error.
 */
class ConnectionDisconnectErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String BLOCK_ERASE_TRIGGER = "test_block_disconnect_erase";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionAuditRepository connectionAuditRepository;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Workspace workspace;
    private Connection connection;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        User owner = persistUser("disconnect-erase-owner-" + suffix);
        workspace = createWorkspace(
                "disconnect-erase-" + suffix, "Disconnect Erase", "erase-org-" + suffix, AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        long installationId = System.nanoTime();
        Connection github = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, workspace.getAccountLogin(), null, Set.of()));
        github.setCredentials(new InstallationCredential(installationId, "app"), credentialConverter);
        github.setState(IntegrationState.ACTIVE);
        connection = connectionRepository.save(github);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(workspace.getAccountLogin() + "/disconnect-erase");
        monitor.setWorkspace(workspace);
        repositoryToMonitorRepository.save(monitor);
    }

    @AfterEach
    void dropEraseBlocker() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + BLOCK_ERASE_TRIGGER + " ON repository_to_monitor");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + BLOCK_ERASE_TRIGGER + "()");
    }

    @Test
    @WithAdminUser
    void shouldKeepConnectionActiveWithCredentialsWhenLocalEraseFails() {
        jdbcTemplate.execute("CREATE FUNCTION " + BLOCK_ERASE_TRIGGER
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'erase blocked'; END $$");
        jdbcTemplate.execute("CREATE TRIGGER " + BLOCK_ERASE_TRIGGER
                + " BEFORE DELETE ON repository_to_monitor FOR EACH ROW WHEN (OLD.workspace_id = "
                + workspace.getId() + ") EXECUTE FUNCTION " + BLOCK_ERASE_TRIGGER + "()");

        disconnect().expectStatus().is5xxServerError().expectBody(Void.class);

        Connection reloaded = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(reloaded.getCredentialsEncrypted()).isNotNull();
        assertThat(connectionAuditRepository.findByWorkspaceId(workspace.getId()))
                .noneSatisfy(audit -> assertThat(audit.getEventType()).isEqualTo("DISCONNECT"));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .hasSize(1);
    }

    @Test
    @WithAdminUser
    void shouldEraseAndUninstallWhenProviderTeardownFails() {
        var logger = (Logger) LoggerFactory.getLogger(ConnectionService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            disconnect().expectStatus().isOk().expectBody(Void.class);
        } finally {
            logger.detachAppender(events);
            events.stop();
        }

        assertThat(events.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("GitHub App credentials not configured");
        });
        Connection reloaded = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(reloaded.getCredentialsEncrypted()).isNull();
        assertThat(connectionAuditRepository.findByWorkspaceId(workspace.getId()))
                .anySatisfy(audit -> assertThat(audit.getEventType()).isEqualTo("DISCONNECT"));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
    }

    private WebTestClient.ResponseSpec disconnect() {
        return webTestClient
                .patch()
                .uri("/workspaces/{slug}/connections/{id}/status", workspace.getWorkspaceSlug(), connection.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(Map.of("state", "UNINSTALLED"))
                .exchange();
    }
}
