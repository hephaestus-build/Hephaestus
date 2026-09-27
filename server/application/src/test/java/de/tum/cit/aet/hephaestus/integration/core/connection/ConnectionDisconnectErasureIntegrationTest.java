package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admin disconnect of a GitHub connection through the real endpoint, strategy and SCM eraser, on
 * PostgreSQL. No GitHub App credentials are configured in tests, so the provider teardown always fails
 * and logs a warning. Triggers make the disconnect fail with a genuine SQL error, either while erasing
 * or only at commit, after every statement has succeeded. The workspace purge's strict teardown is
 * driven directly inside a purge-shaped transaction that erases the mirror first.
 */
class ConnectionDisconnectErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String BLOCK = "test_block_disconnect";

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

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ScmWorkspaceContentEraser scmWorkspaceContentEraser;

    @Autowired
    private PlatformTransactionManager transactionManager;

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
    void dropBlockers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + BLOCK + " ON repository_to_monitor");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + BLOCK + " ON connection_audit");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS " + BLOCK + "()");
    }

    @Test
    @WithAdminUser
    void shouldKeepConnectionActiveWithCredentialsWhenLocalEraseFails() {
        createBlockFunction();
        jdbcTemplate.execute("CREATE TRIGGER " + BLOCK
                + " BEFORE DELETE ON repository_to_monitor FOR EACH ROW WHEN (OLD.workspace_id = "
                + workspace.getId() + ") EXECUTE FUNCTION " + BLOCK + "()");

        assertThat(disconnectReturningProviderWarnings(false)).isEmpty();

        assertConnectionUntouched();
    }

    @Test
    @WithAdminUser
    void shouldLeaveProviderUntouchedWhenDisconnectFailsToCommit() {
        createBlockFunction();
        jdbcTemplate.execute("CREATE CONSTRAINT TRIGGER " + BLOCK
                + " AFTER INSERT ON connection_audit DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.connection_id = "
                + connection.getId() + ") EXECUTE FUNCTION " + BLOCK + "()");

        assertThat(disconnectReturningProviderWarnings(false)).isEmpty();

        assertConnectionUntouched();
    }

    @Test
    @WithAdminUser
    void shouldEraseAndUninstallWhenProviderTeardownFails() {
        assertThat(disconnectReturningProviderWarnings(true))
                .singleElement()
                .satisfies(event ->
                        assertThat(event.getFormattedMessage()).contains("GitHub App credentials not configured"));

        Connection reloaded = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(reloaded.getCredentialsEncrypted()).isNull();
        assertThat(connectionAuditRepository.findByWorkspaceId(workspace.getId()))
                .anySatisfy(audit -> assertThat(audit.getEventType()).isEqualTo("DISCONNECT"));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
    }

    @Test
    void shouldTearDownOutsideTheTransactionAndRollBackWhenPurgeTeardownFails() {
        List<Boolean> transactionActive = new ArrayList<>();
        ConnectionStrategy provider = Mockito.mock(ConnectionStrategy.class);
        when(provider.kind()).thenReturn(IntegrationKind.GITHUB);
        when(provider.prepareProviderTeardown(any())).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of((Runnable) () -> {
                transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                throw new IllegalStateException("provider unavailable");
            });
        });
        var purge = new ConnectionPurgeContributor(connectionRepository, connectionService, List.of(provider));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    scmWorkspaceContentEraser.eraseWorkspaceScmMirror(workspace.getId());
                    purge.deleteWorkspaceData(workspace.getId());
                }))
                .isInstanceOf(WorkspacePurgeBlockedException.class);

        assertThat(transactionActive).containsExactly(false, false);
        assertConnectionUntouched();
    }

    private void createBlockFunction() {
        jdbcTemplate.execute("CREATE FUNCTION " + BLOCK
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'disconnect blocked'; END $$");
    }

    private void assertConnectionUntouched() {
        Connection reloaded = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(reloaded.getCredentialsEncrypted()).isNotNull();
        assertThat(connectionAuditRepository.findByWorkspaceId(workspace.getId()))
                .noneSatisfy(audit -> assertThat(audit.getToState()).isEqualTo(IntegrationState.UNINSTALLED));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .hasSize(1);
    }

    private List<ILoggingEvent> disconnectReturningProviderWarnings(boolean succeeds) {
        var logger = (Logger) LoggerFactory.getLogger(ConnectionService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var response = webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/connections/{id}/status", workspace.getWorkspaceSlug(), connection.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("state", "UNINSTALLED"))
                    .exchange();
            if (succeeds) {
                response.expectStatus().isOk().expectBody(Void.class);
            } else {
                response.expectStatus().is5xxServerError().expectBody(Void.class);
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
        return events.list.stream()
                .filter(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().startsWith("Provider teardown failed"))
                .toList();
    }
}
