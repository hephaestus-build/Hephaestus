package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ProviderResourceRepairAdminService;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ReleaseProviderResourceRepairRequestDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ConnectionProviderRepairRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairDispatcher;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ConnectionDisconnectErasureIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private ProviderResourceRepairAdminService repairAdmin;

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

    // A fixture changes the schema as its owner: the application role cannot.
    private final JdbcTemplate schemaOwner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectProvider<ProviderTeardown> teardowns;

    @Autowired
    private List<ConnectionStrategy> strategies;

    @Autowired
    private ProviderResourceRepairs resourceRepairs;

    @Autowired
    private ProviderResourceRepairDispatcher repairDispatcher;

    @Autowired
    private ProviderResourceRepairRepository repairRows;

    @Autowired
    private ConnectionProviderRepairRepository repairReferences;

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
        monitor.setNativeId(1L);
        monitor.setNameWithOwner(workspace.getAccountLogin() + "/disconnect-erase");
        monitor.setWorkspace(workspace);
        repositoryToMonitorRepository.save(monitor);
    }

    @AfterEach
    void dropBlockers() {
        schemaOwner.execute("DROP TRIGGER IF EXISTS " + BLOCK + " ON repository_to_monitor");
        schemaOwner.execute("DROP TRIGGER IF EXISTS " + BLOCK + " ON connection_audit");
        schemaOwner.execute("DROP FUNCTION IF EXISTS " + BLOCK + "()");
    }

    @Test
    @WithAdminUser
    void shouldKeepConnectionActiveWithCredentialsWhenLocalEraseFails() {
        createBlockFunction();
        schemaOwner.execute("CREATE TRIGGER "
                + BLOCK
                + " BEFORE DELETE ON repository_to_monitor FOR EACH ROW WHEN"
                + " (OLD.workspace_id = "
                + workspace.getId()
                + ") EXECUTE FUNCTION "
                + BLOCK
                + "()");

        assertThat(disconnectReturningProviderWarnings()).isEmpty();

        assertConnectionUntouched();
        assertThat(ownedRepairCount()).isZero();
    }

    @Test
    @WithAdminUser
    void shouldLeaveProviderUntouchedWhenDisconnectFailsToCommit() {
        createBlockFunction();
        schemaOwner.execute("CREATE CONSTRAINT TRIGGER "
                + BLOCK
                + " AFTER INSERT ON connection_audit DEFERRABLE INITIALLY DEFERRED FOR EACH"
                + " ROW WHEN (NEW.connection_id = "
                + connection.getId()
                + ") EXECUTE FUNCTION "
                + BLOCK
                + "()");

        assertThat(disconnectReturningProviderWarnings()).isEmpty();

        assertConnectionUntouched();
        assertThat(ownedRepairCount()).isZero();
    }

    @Test
    void shouldRefuseTheSameInstallationWhileRemovalIsUnresolved() {
        disconnect();
        ConnectionConfig.GitHubAppConfig old = (ConnectionConfig.GitHubAppConfig) connection.getConfig();
        assertThatThrownBy(() -> connectionService.upsertGitHubAppConnection(
                        workspace,
                        1L,
                        Objects.requireNonNull(old.installationId()),
                        workspace.getAccountLogin(),
                        UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(connectionRepository
                        .findById(connection.getId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(ownedRepairCount()).isOne();
    }

    @Test
    void shouldNotRetargetTheOldRemovalWhenAFreshInstallationIsAdopted() {
        disconnect();
        var old = repairRows
                .findById(repairReferences
                        .findByWorkspaceIdAndConnection_Id(workspace.getId(), connection.getId())
                        .getFirst()
                        .getRepair()
                        .getId())
                .orElseThrow()
                .resource();
        long freshInstallation = System.nanoTime();
        Connection fresh = connectionService.upsertGitHubAppConnection(
                workspace,
                1L,
                freshInstallation,
                workspace.getAccountLogin(),
                UUID.randomUUID().toString());
        assertThat(fresh.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(((ConnectionConfig.GitHubAppConfig) fresh.getConfig()).installationId())
                .isEqualTo(freshInstallation);
        assertThat(repairRows.findByIdentityHash(old.identity()).orElseThrow().resource())
                .isEqualTo(old);
    }

    @Test
    void shouldEraseAnExpiredCredentialWithoutReleasingItsUnresolvedIdentity() {
        disconnect();
        jdbcTemplate.update(
                "UPDATE connection SET provider_teardown_requested_at = ? WHERE workspace_id = ? AND id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))),
                workspace.getId(),
                connection.getId());
        ProviderTeardown expiry = new ProviderTeardown(
                connectionRepository,
                credentialConverter,
                resourceRepairs,
                repairDispatcher,
                transactionManager,
                Clock.systemUTC());
        expiry.expire();
        Connection erased = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(erased.getCredentialsEncrypted()).isNull();
        assertThat(ownedRepairCount()).isOne();
    }

    @Test
    void shouldEraseLocallyAndRetainTheFenceWhenTheProviderCannotConfirmRemoval() {
        var purge = new ConnectionPurgeContributor(connectionRepository, connectionService, teardowns, strategies);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> purge.deleteWorkspaceData(workspace.getId()));
        Connection erased = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(erased.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(erased.getCredentialsEncrypted()).isNull();
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(ownedRepairCount()).isOne();
    }

    @Test
    void shouldNotRecreateAReleasedFenceOnAnOldWakeOrCredentialExpiry() {
        disconnect();
        var current = connectionRepository.findById(connection.getId()).orElseThrow();
        long generation = current.getCredentialsGeneration();
        var ref = repairReferences
                .findByWorkspaceIdAndConnection_Id(workspace.getId(), current.getId())
                .getFirst();
        var repair = repairRows.findById(ref.getRepair().getId()).orElseThrow();
        var actor = persistAccount("repair release " + UUID.randomUUID());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                Objects.requireNonNull(actor.getId()).toString(), new String[] {"app_admin"}, "repair-release"));
        repairAdmin.release(
                workspace.getId(),
                current.getId(),
                repair.getId(),
                new ReleaseProviderResourceRepairRequestDTO(true, repair.getIdentityHash()));
        ProviderTeardown expiry = Objects.requireNonNull(teardowns.getIfAvailable());
        expiry.on(new ProviderTeardownRequested(current.getId(), workspace.getId(), generation));
        assertThat(ownedRepairCount()).isZero();
        jdbcTemplate.update(
                "UPDATE connection SET provider_teardown_requested_at = ? WHERE workspace_id = ? AND id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofHours(25))),
                workspace.getId(),
                current.getId());
        jdbcTemplate.update(
                "UPDATE shedlock SET lock_until = TIMESTAMP '1970-01-01' WHERE name = 'provider-teardown-expiry'");
        expiry.expire();
        expiry.on(new ProviderTeardownRequested(current.getId(), workspace.getId(), generation));
        assertThat(ownedRepairCount()).isZero();
        assertThat(connectionRepository.findById(current.getId()).orElseThrow().getCredentialsEncrypted())
                .isNull();
    }

    private @Nullable Long ownedRepairCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM connection_provider_repair WHERE workspace_id = ? AND connection_id = ?",
                Long.class,
                workspace.getId(),
                connection.getId());
    }

    private void createBlockFunction() {
        schemaOwner.execute("CREATE FUNCTION "
                + BLOCK
                + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION"
                + " 'disconnect blocked'; END $$");
    }

    private void assertConnectionUntouched() {
        Connection reloaded = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(reloaded.getCredentialsEncrypted()).isNotNull();
        assertThat(connectionAuditRepository.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        workspace.getId(), connection.getId()))
                .noneSatisfy(audit -> assertThat(audit.getToState()).isEqualTo(IntegrationState.UNINSTALLED));
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .hasSize(1);
    }

    private List<ILoggingEvent> disconnectReturningProviderWarnings() {
        return captureProviderWarnings(() -> {
            var response = webTestClient
                    .patch()
                    .uri("/workspaces/{slug}/connections/{id}/status", workspace.getWorkspaceSlug(), connection.getId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .bodyValue(Map.of("state", "UNINSTALLED"))
                    .exchange();
            response.expectStatus().is5xxServerError().expectBody(Void.class);
        });
    }

    private void disconnect() {
        var actor = persistAccount("disconnect administrator " + UUID.randomUUID());
        connectionService.disconnect(
                connection,
                TransitionRequest.byAccount(
                        IntegrationState.UNINSTALLED,
                        "DISCONNECT",
                        "ADMIN",
                        actor.getId(),
                        UUID.randomUUID().toString(),
                        null),
                strategy(IntegrationKind.GITHUB));
    }

    private ConnectionStrategy strategy(IntegrationKind kind) {
        return strategies.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private List<ILoggingEvent> captureProviderWarnings(Runnable disconnect) {
        var logger = (Logger) LoggerFactory.getLogger(ProviderTeardown.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            disconnect.run();
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
