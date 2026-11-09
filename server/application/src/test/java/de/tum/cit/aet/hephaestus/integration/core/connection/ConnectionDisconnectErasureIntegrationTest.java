package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.ScmWorkspaceContentEraser;
import de.tum.cit.aet.hephaestus.notification.NotificationRedeliveryJob;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    /** A fixture changes the schema as its owner: the application role cannot. */
    private final JdbcTemplate schemaOwner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private ScmWorkspaceContentEraser scmWorkspaceContentEraser;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CredentialReader credentialReader;

    @Autowired
    private ObjectProvider<ProviderTeardown> teardowns;

    @Autowired
    private List<ConnectionStrategy> strategies;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private IntegrationAttentionService attention;

    @Autowired
    private EntityManager entityManager;

    private final Runnable removal = mock(Runnable.class);

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
    }

    @Test
    void shouldKeepTheCredentialUnusableAndRemoveTheInstallationWhenTheFailingProviderRecovers() {
        double revoked = outcomes("revoked");

        // The test profile holds no GitHub App key, so the application's own teardown fails like an unreachable GitHub.
        assertThat(captureProviderWarnings(this::disconnect))
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage()).contains("failure=IllegalStateException"));

        Connection pending = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(pending.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(pending.getCredentialsEncrypted()).isNotNull();
        assertThat(pending.getProviderTeardownRequestedAt()).isNotNull();
        assertThat(repositoryToMonitorRepository.findByWorkspaceId(workspace.getId()))
                .isEmpty();
        assertThat(publications()).isEqualTo(1);
        // Every reader but the teardown gets nothing for a disconnected connection.
        assertThat(credentialReader.credentialsOf(pending)).isEmpty();
        assertThat(connectionService.findBearerToken(workspace.getId(), connection.getId()))
                .isEmpty();

        recovered().on(owed());

        Connection released = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(released.getCredentialsEncrypted()).isNull();
        assertThat(released.getProviderTeardownRequestedAt()).isNull();
        verify(removal, times(1)).run();
        assertThat(outcomes("revoked") - revoked).isEqualTo(1.0);
        // The failed publication now finds nothing owed, and the registry completes it.
        redeliverTeardown();
        assertThat(publications()).isZero();
    }

    @Test
    void shouldNeverRemoveWhatAReconnectUsesWhenTheConnectionReconnectsDuringAPendingTeardown() {
        long installationId = Long.parseLong(connection.getInstanceKey());
        double adopted = outcomes("adopted");
        disconnect();
        ProviderTeardownRequested beforeReconnect = owed();

        reconnect(installationId);
        recovered().on(beforeReconnect);

        Connection reconnected =
                connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(reconnected.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(reconnected.getProviderTeardownRequestedAt()).isNull();
        assertThat(credentialReader.credentialsOf(reconnected))
                .contains(new InstallationCredential(installationId, "reconnected"));
        verify(removal, never()).run();
        assertThat(outcomes("adopted") - adopted).isEqualTo(1.0);
    }

    @Test
    void shouldIgnoreAnEarlierDisconnectsEventWhenItArrivesDuringALaterDisconnect() {
        disconnect();
        ProviderTeardownRequested first = owed();
        reconnect(Long.parseLong(connection.getInstanceKey()));
        connection = connectionRepository.findById(connection.getId()).orElseThrow();
        disconnect();

        recovered().on(first);

        verify(removal, never()).run();
        assertThat(connectionRepository
                        .findById(connection.getId())
                        .orElseThrow()
                        .getProviderTeardownRequestedAt())
                .isNotNull();
    }

    @Test
    void shouldClearTheCredentialAndTellTheAdminWhenTheProviderFailsForTheWholeWindow() {
        double unrevoked = outcomes("purged_unrevoked");
        disconnect();
        jdbcTemplate.update(
                "UPDATE connection SET provider_teardown_requested_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(ProviderTeardown.WINDOW).minusSeconds(60)),
                connection.getId());

        recovered().on(owed());

        Connection released = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(released.getCredentialsEncrypted()).isNull();
        assertThat(released.getProviderTeardownRequestedAt()).isNull();
        assertThat(released.getAttentionProblem()).isEqualTo(Problem.PROVIDER_CLEANUP_UNCONFIRMED);
        assertThat(outcomes("purged_unrevoked") - unrevoked).isEqualTo(1.0);
        verify(removal, never()).run();
    }

    @Test
    void shouldRemoveAPendingTeardownsFootprintBeforeAWorkspacePurgeErasesTheMirror() {
        disconnect();
        var beans = new StaticListableBeanFactory();
        beans.addBean("providerTeardown", recovered());
        var purge = new ConnectionPurgeContributor(
                connectionRepository,
                connectionService,
                credentialReader,
                beans.getBeanProvider(ProviderTeardown.class),
                strategies);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> purge.deleteWorkspaceData(workspace.getId()));

        Connection erased = connectionRepository.findById(connection.getId()).orElseThrow();
        assertThat(erased.getCredentialsEncrypted()).isNull();
        assertThat(erased.getProviderTeardownRequestedAt()).isNull();
        verify(removal, times(1)).run();
    }

    @Test
    void shouldTearDownOutsideTheTransactionAndRollBackWhenPurgeTeardownFails() {
        List<Boolean> transactionActive = new ArrayList<>();
        ConnectionStrategy provider = mock(ConnectionStrategy.class);
        when(provider.kind()).thenReturn(IntegrationKind.GITHUB);
        when(provider.prepareProviderTeardown(any(), any())).thenAnswer(invocation -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.of((Runnable) () -> {
                transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                throw new IllegalStateException("provider unavailable");
            });
        });
        var purge = new ConnectionPurgeContributor(
                connectionRepository, connectionService, credentialReader, teardowns, List.of(provider));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    scmWorkspaceContentEraser.eraseWorkspaceScmMirror(workspace.getId());
                    purge.deleteWorkspaceData(workspace.getId());
                }))
                .isInstanceOf(WorkspacePurgeBlockedException.class);

        assertThat(transactionActive).containsExactly(false, false);
        assertConnectionUntouched();
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
        assertThat(connectionAuditRepository.findByWorkspaceId(workspace.getId()))
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

    /** A teardown whose provider now confirms the removal. */
    private ProviderTeardown recovered() {
        ConnectionStrategy github = mock(ConnectionStrategy.class);
        when(github.kind()).thenReturn(IntegrationKind.GITHUB);
        when(github.prepareProviderTeardown(any(), any())).thenReturn(Optional.of(removal));
        var teardown = new ProviderTeardown(
                connectionRepository,
                credentialReader,
                credentialConverter,
                attention,
                List.of(github),
                meterRegistry,
                transactionManager,
                Clock.systemUTC());
        ReflectionTestUtils.setField(teardown, "entityManager", entityManager);
        return teardown;
    }

    /** The teardown the connection's latest disconnect owes. */
    private ProviderTeardownRequested owed() {
        return new ProviderTeardownRequested(
                connection.getId(),
                workspace.getId(),
                connectionRepository.findById(connection.getId()).orElseThrow().getCredentialsGeneration());
    }

    /** A reconnect revives the same connection and stores what the provider returned. */
    private void reconnect(long installationId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Connection revived =
                    connectionRepository.findById(connection.getId()).orElseThrow();
            connectionService.transition(
                    revived,
                    new TransitionRequest(
                            IntegrationState.ACTIVE,
                            "INITIATE",
                            "SYSTEM",
                            "reconnect",
                            UUID.randomUUID().toString(),
                            null));
            revived.setCredentials(new InstallationCredential(installationId, "reconnected"), credentialConverter);
        });
    }

    private ConnectionStrategy strategy(IntegrationKind kind) {
        return strategies.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    /** The staleness monitor marks a failed teardown FAILED; the redelivery job takes it after its minimum age. */
    private void redeliverTeardown() {
        jdbcTemplate.update(
                "UPDATE event_publication SET status = 'FAILED', publication_date = ? WHERE serialized_event LIKE ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(10))),
                publicationPattern());
        new NotificationRedeliveryJob(failedPublications).resubmitFailed();
    }

    private @Nullable Integer publications() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_publication WHERE serialized_event LIKE ?",
                Integer.class,
                publicationPattern());
    }

    private String publicationPattern() {
        return "%\"connectionId\":" + connection.getId() + ",%";
    }

    private double outcomes(String outcome) {
        var counter = meterRegistry
                .find(IntegrationCoreMetrics.INTEGRATION_PROVIDER_TEARDOWN)
                .tags("kind", "github", "outcome", outcome)
                .counter();
        return counter == null ? 0 : counter.count();
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
