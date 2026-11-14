package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredDisconnects;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredWorkspacePurges;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/**
 * An erasure after the recovery point, a restore to that point, then the recovery profile: the restored data is erased
 * again. The restore puts back every row of the workspace as the backup held it, as a physical restore does: no
 * trigger or foreign key runs while it writes.
 */
class ErasureRecoveryRestoreIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @TempDir
    Path directory;

    @Autowired
    private PersonDataService service;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private CredentialBundleConverter credentials;

    @Autowired
    private List<ConnectionStrategy> strategies;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private SyncJobRepository syncJobs;

    private Workspace workspace;
    private Connection connection;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        var owner = persistUser("restore-erase-owner-" + suffix);
        workspace = createWorkspace(
                "restore-erase-" + suffix, "Restore Erase", "restore-org-" + suffix, AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        long installationId = System.nanoTime();
        var github = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, workspace.getAccountLogin(), null, Set.of()));
        github.setCredentials(new InstallationCredential(installationId, "app"), credentials);
        ConnectionFixtures.reachState(github, IntegrationState.ACTIVE);
        connection = connections.save(github);
        var monitor = new RepositoryToMonitor();
        monitor.setNativeId(1L);
        monitor.setNameWithOwner(workspace.getAccountLogin() + "/restore-erase");
        monitor.setWorkspace(workspace);
        monitors.save(monitor);
    }

    @Test
    void shouldDisconnectAgainAConnectionThatARestoreBroughtBack() throws Exception {
        var backup = Backup.of(jdbc, workspace.getId());
        connectionService.disconnect(
                connection,
                ConnectionService.TransitionRequest.byAccount(
                        IntegrationState.UNINSTALLED,
                        "DISCONNECT",
                        "ADMIN",
                        persistAccount("restore administrator " + UUID.randomUUID())
                                .getId(),
                        UUID.randomUUID().toString(),
                        null),
                strategy(IntegrationKind.GITHUB));
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();

        backup.restore();
        assertThat(state()).as("the restore brought the connection back").isEqualTo(IntegrationState.ACTIVE);
        assertThat(monitors.findByWorkspaceId(workspace.getId())).hasSize(1);

        String manifest = """
                {"version":2,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[{"workspaceId":%d,"connectionId":%d}]}
                """.formatted(workspace.getId(), connection.getId());
        recover(manifest);

        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(connections.findById(connection.getId()).orElseThrow().getCredentialsEncrypted())
                .as("erasure releases the credential at once")
                .isNull();
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);

        // A later backup holds the connection reconnected on the same row, beside this recovery's audit history.
        var history = jdbc.queryForList("SELECT * FROM connection_audit WHERE workspace_id = ?", workspace.getId());
        backup.with("connection_audit", history).restore();
        assertThat(state()).isEqualTo(IntegrationState.ACTIVE);
        recover(manifest);
        assertThat(state())
                .as("an earlier recovery's audit row does not make the disconnect look recorded")
                .isEqualTo(IntegrationState.UNINSTALLED);
    }

    @Test
    void shouldPurgeAgainAWorkspaceThatARestoreBroughtBack() throws Exception {
        var backup = Backup.of(jdbc, workspace.getId());
        lifecycle.purgeWorkspace(workspace.getWorkspaceSlug());
        assertThat(status()).isEqualTo("PURGED");

        backup.restore();
        assertThat(status()).as("the restore brought the workspace back").isEqualTo("ACTIVE");
        assertThat(monitors.findByWorkspaceId(workspace.getId())).hasSize(1);
        assertThat(memberships()).isPositive();
        // Work in flight at the recovery point stays in the restore; the guards that would wait for it do not run.
        syncJobs.saveAndFlush(new SyncJob(
                workspace,
                connection,
                IntegrationKind.GITHUB,
                SyncJobType.RECONCILIATION,
                SyncJobTrigger.MANUAL,
                null));

        String manifest = """
                {"version":2,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[{"workspaceId":%d}],"disconnects":[]}
                """.formatted(workspace.getId());
        recover(manifest);

        assertThat(status()).isEqualTo("PURGED");
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();
        assertThat(memberships()).isZero();
        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);
        recover(manifest);
        assertThat(status()).isEqualTo("PURGED");
    }

    private void recover(String content) throws Exception {
        Path manifest = Files.writeString(directory.resolve("recovery-" + UUID.randomUUID() + ".json"), content);
        var environment = new MockEnvironment()
                .withProperty(RuntimeRole.SERVER_PROPERTY, "true")
                .withProperty(ConditionalOnBackgroundWork.PROPERTY, "false");
        try (var context = new GenericApplicationContext()) {
            context.registerBean(
                    PersonDataRecovery.class,
                    () -> new PersonDataRecovery(
                            service,
                            resolver,
                            mapper,
                            environment,
                            beans.getBean(PersonDataRequestRepository.class),
                            beans.getBean(RestoredWorkspacePurges.class),
                            beans.getBean(RestoredDisconnects.class),
                            new PersonDataRecoveryProperties(manifest.toString())));
            context.refresh();
            assertThat(context.getBean(PersonDataRecovery.class).isRunning()).isTrue();
        }
    }

    private IntegrationState state() {
        return connections.findById(connection.getId()).orElseThrow().getState();
    }

    private String status() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()));
    }

    private int memberships() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM workspace_membership WHERE workspace_id = ?", Integer.class, workspace.getId());
        return count == null ? 0 : count;
    }

    private ConnectionStrategy strategy(IntegrationKind kind) {
        return strategies.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Every row of one workspace as a backup holds it: the workspace row and each row that carries its workspace, as
     * every workspace-owned table does. A provider removal that the erasure recorded is global, so the restore also
     * deletes the repairs that the workspace's links name and the backup did not hold.
     */
    private record Backup(
            JdbcTemplate jdbc, long workspaceId, Map<String, List<Map<String, Object>>> rows, Set<String> generated) {

        static Backup of(JdbcTemplate jdbc, long workspaceId) {
            var generated = jdbc.queryForList("""
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                     WHERE table_schema = current_schema() AND is_generated = 'ALWAYS'
                    """, String.class)
                    .stream()
                    .map(Objects::requireNonNull)
                    .collect(Collectors.toUnmodifiableSet());
            var rows = new LinkedHashMap<String, List<Map<String, Object>>>();
            rows.put("workspace", jdbc.queryForList("SELECT * FROM workspace WHERE id = ?", workspaceId));
            for (String table : workspaceTables(jdbc)) {
                rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE workspace_id = ?", workspaceId));
            }
            return new Backup(jdbc, workspaceId, rows, generated);
        }

        private static List<String> workspaceTables(JdbcTemplate jdbc) {
            return jdbc.queryForList("""
                    SELECT c.table_name FROM information_schema.columns c
                      JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                     WHERE c.table_schema = current_schema() AND c.column_name = 'workspace_id'
                       AND t.table_type = 'BASE TABLE'
                     ORDER BY c.table_name
                    """, String.class)
                    .stream()
                    .map(Objects::requireNonNull)
                    .toList();
        }

        /** The same backup, with the rows of one table as a later backup holds them. */
        Backup with(String table, List<Map<String, Object>> tableRows) {
            var changed = new LinkedHashMap<>(rows);
            changed.put(table, tableRows);
            return new Backup(jdbc, workspaceId, changed, generated);
        }

        void restore() throws SQLException {
            var held = Objects.requireNonNull(rows.get("connection_provider_repair")).stream()
                    .map(row -> row.get("repair_id"))
                    .toList();
            var administrator = PostgreSQLTestContainer.administrator();
            try (var session = DriverManager.getConnection(
                    administrator.jdbcUrl(), administrator.username(), administrator.password())) {
                session.setAutoCommit(false);
                var restore = new JdbcTemplate(new SingleConnectionDataSource(session, true));
                restore.execute("SET LOCAL session_replication_role = replica");
                restore.execute(
                        "SET LOCAL search_path TO " + jdbc.queryForObject("SELECT current_schema()", String.class));
                for (UUID recorded : restore
                        .queryForList(
                                "SELECT repair_id FROM connection_provider_repair WHERE workspace_id = ?",
                                UUID.class,
                                workspaceId)
                        .stream()
                        .map(Objects::requireNonNull)
                        .toList()) {
                    if (!held.contains(recorded))
                        restore.update("DELETE FROM provider_resource_repair WHERE id = ?", recorded);
                }
                for (var table : rows.entrySet()) {
                    String key = table.getKey().equals("workspace") ? "id" : "workspace_id";
                    restore.update("DELETE FROM " + table.getKey() + " WHERE " + key + " = ?", workspaceId);
                    for (var row : table.getValue()) insert(restore, table.getKey(), row, generated);
                }
                session.commit();
            }
        }

        /** A generated column is computed again from the restored row, as a restore does. */
        private static void insert(JdbcTemplate restore, String table, Map<String, Object> row, Set<String> generated) {
            var stored = row.entrySet().stream()
                    .filter(column -> !generated.contains(table + "." + column.getKey()))
                    .toList();
            var columns =
                    String.join(", ", stored.stream().map(Map.Entry::getKey).toList());
            var values = String.join(", ", stored.stream().map(column -> "?").toList());
            restore.update(
                    "INSERT INTO " + table + " (" + columns + ") OVERRIDING SYSTEM VALUE VALUES (" + values + ")",
                    stored.stream().map(Map.Entry::getValue).toArray());
        }
    }
}
