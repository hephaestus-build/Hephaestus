package de.tum.cit.aet.hephaestus.integration.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.audit.ConfigAuditRetentionJob;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.database.AuthEventPartitionMaintenance;
import de.tum.cit.aet.hephaestus.core.runtime.ShedLockConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.TestCacheConfiguration;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Validates the production schema, its JPA mappings, and database-enforced contracts. */
@DataJpaTest(
        properties = {
            "spring.liquibase.enabled=true",
            "spring.liquibase.change-log=classpath:db/master.xml",
            "spring.liquibase.contexts=dev,prod",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.jpa.defer-datasource-initialization=false",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    TestCacheConfiguration.class,
    ConfigAuditRetentionJob.class,
    ShedLockConfig.class,
    AuthEventPartitionMaintenance.class,
    ProductionSchemaContractIntegrationTest.JsonConfiguration.class,
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Tag("database")
class ProductionSchemaContractIntegrationTest {

    private static final String ACCOUNT_FEATURE = "run_practice_review";
    private static final String IDENTITY_SUBJECT = "583231";

    private static final TestDatabase DATABASE = createSchemaDatabase();

    private static TestDatabase createSchemaDatabase() {
        var database = PostgreSQLTestContainer.createDatabase("hephaestus_liquibase_validation");
        PostgreSQLTestContainer.migrateDatabase(database, "dev,prod");
        return database;
    }

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::jdbcUrl);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LockProvider locks;

    @Autowired
    private AuthEventPartitionMaintenance partitionMaintenance;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConfigAuditRetentionJob retentionJob;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    @Test
    void shouldKeepTheActivityCoveringIndexAndRemoveItsRedundantBaseline() {
        assertThat(jdbcTemplate.queryForObject("""
            SELECT indnkeyatts FROM pg_index
            WHERE indexrelid = 'public.idx_activity_event_workspace_covering'::regclass
            """, Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList("""
            SELECT a.attname FROM pg_index i
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS columns(number, position)
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = columns.number
            WHERE i.indexrelid = 'public.idx_activity_event_workspace_covering'::regclass
            ORDER BY columns.position
            """, String.class))
                .containsExactly(
                        "workspace_id",
                        "occurred_at",
                        "actor_id",
                        "event_type",
                        "target_type",
                        "target_id",
                        "repository_id");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT to_regclass('public.idx_activity_event_workspace_occurred')::text", String.class))
                .isNull();
    }

    @Test
    void shouldAcquireAndReleaseSchedulerLocksAgainstTheMigratedSchema() {
        String name = "schema-" + UUID.randomUUID();
        var configuration = new LockConfiguration(Instant.now(), name, Duration.ofMinutes(1), Duration.ZERO);
        var first = locks.lock(configuration).orElseThrow();
        try {
            try {
                assertThat(locks.lock(configuration)).isEmpty();
            } finally {
                first.unlock();
            }
            var next = locks.lock(configuration).orElseThrow();
            next.unlock();
        } finally {
            jdbcTemplate.update("DELETE FROM shedlock WHERE name = ?", name);
        }
    }

    @Test
    void shouldRecreateTheNewestAuthEventPartitionWhenMaintenanceRuns() {
        String newest = "partman.show_partition_name('public.auth_event', (now() + interval '2 months')::text)";
        String partition = jdbcTemplate.queryForObject(
                "SELECT partition_schema || '.' || partition_table FROM " + newest, String.class);
        jdbcTemplate.execute("DROP TABLE " + partition);

        partitionMaintenance.maintain();

        assertThat(jdbcTemplate.queryForObject("SELECT table_exists FROM " + newest, Boolean.class))
                .isTrue();
    }

    @Test
    void shouldPreserveResultTenantAndProvenanceForeignKeys() {
        List<@Nullable String> foreignKeys = jdbcTemplate.queryForList("""
            SELECT DISTINCT rc.constraint_name || ':' || parent.table_name || ':' || rc.delete_rule
            FROM information_schema.referential_constraints rc
            JOIN information_schema.key_column_usage child
              ON child.constraint_schema = rc.constraint_schema AND child.constraint_name = rc.constraint_name
            JOIN information_schema.constraint_column_usage parent
              ON parent.constraint_schema = rc.unique_constraint_schema
             AND parent.constraint_name = rc.unique_constraint_name
            WHERE rc.constraint_schema = current_schema()
              AND child.table_name = 'result'
            """, String.class);
        assertThat(foreignKeys)
                .noneMatch(foreignKey -> Objects.requireNonNull(foreignKey).endsWith(":CASCADE"))
                .contains(
                        "sfk_result_workspace:workspace:RESTRICT",
                        "sfk_result_practice:practice:RESTRICT",
                        "sfk_result_version:practice_version:RESTRICT",
                        "sfk_result_review:review:RESTRICT");

        assertColumnRequired("result", "workspace_id");
    }

    @Test
    void accountFeatureIsScopedToTheProviderIdentity() {
        long providerA = provider("https://provider-a.example");
        long providerB = provider("https://provider-b.example");
        Account enabled = accountRepository.save(new Account("Provider A account"));
        Account disabled = accountRepository.save(new Account("Provider B account"));
        accountFeatureRepository.save(new AccountFeature(Objects.requireNonNull(enabled.getId()), ACCOUNT_FEATURE));
        link(enabled, providerA);
        link(disabled, providerB);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerA, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isTrue();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerB, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        providerA, IDENTITY_SUBJECT, "notification_access"))
                .isFalse();
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        999L, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
    }

    @Test
    void disabledIdentityDoesNotCarryAccountFeature() {
        long provider = provider("https://disabled-provider.example");
        Account account = accountRepository.save(new Account("Disabled identity account"));
        accountFeatureRepository.save(new AccountFeature(Objects.requireNonNull(account.getId()), ACCOUNT_FEATURE));
        Identity identity = link(account, provider);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        provider, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isTrue();

        identity.setDisabledAt(Instant.now());
        identities.save(identity);

        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        provider, IDENTITY_SUBJECT, ACCOUNT_FEATURE))
                .isFalse();
    }

    private Identity link(Account account, long providerId) {
        Identity identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey(IDENTITY_SUBJECT);
        return identities.save(identity);
    }

    private long provider(String serverUrl) {
        return Objects.requireNonNull(identityProviderRepository
                .save(new IdentityProvider(IdentityProviderType.GITHUB, serverUrl))
                .getId());
    }

    @Test
    @DisplayName("Production Liquibase schema applies cleanly and the JPA entities validate against it")
    void productionSchemaAppliesAndEntitiesValidate() {
        assertColumnExists("workspace", "account_login");
        assertColumnExists("repository_to_monitor", "generated_paths");
        assertColumnExists("connection", "credentials_encrypted");
        assertColumnExists("slack_message", "author_account_id");
        assertColumnExists("slack_thread", "participant_account_ids");
        assertColumnExists("identity_provider", "type");
        assertIndexExists("idx_slack_thread_participants");
        assertIndexExists("idx_slack_message_ingest");
        assertConstraintExists("ck_feedback_send_lease");
        assertConstraintExists("ck_feedback_copy_fence");
        assertConstraintExists("ck_feedback_withheld");
    }

    @Test
    void shouldRejectUnknownScopeModes() {
        long workspaceId = insertWorkspace("coverage-mode-check");

        assertThatCode(() -> setScopeMode(workspaceId, "review_scope_repositories", "SELECTED"))
                .as("a declared mode is what the entity writes")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> setScopeMode(workspaceId, "review_scope_repositories", "EVERYONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workspace_scope_repositories");

        assertThatThrownBy(() -> setScopeMode(workspaceId, "review_scope_people", "EVERYONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workspace_scope_people");

        assertThatThrownBy(() -> setScopeMode(workspaceId, "practice_default_feedback_mode", "INVALID"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_workspace_feedback_mode");

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT review_scope_repositories FROM workspace WHERE id = ?", String.class, workspaceId))
                .as("a refused write leaves the last accepted mode in force")
                .isEqualTo("SELECTED");
    }

    @ParameterizedTest
    @MethodSource("invalidBaseBranches")
    void shouldRejectMalformedBaseBranches(String branches) {
        long workspaceId = insertWorkspace("base-branches-" + UUID.randomUUID());
        Long monitorId = jdbcTemplate.queryForObject(
                "INSERT INTO repository_to_monitor (workspace_id, name_with_owner, native_id, generated_paths) VALUES (?, ?, 12345,"
                        + " '[]'::jsonb) RETURNING id",
                Long.class,
                workspaceId,
                "acme/repository");
        assertThatCode(() -> jdbcTemplate.update(
                        "INSERT INTO review_scope_repository "
                                + "(workspace_id, repository_monitor_id, base_branches) VALUES (?, ?, '[]'::jsonb)",
                        workspaceId,
                        monitorId))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE review_scope_repository SET base_branches = CAST(? AS jsonb) "
                                + "WHERE workspace_id = ? AND repository_monitor_id = ?",
                        branches,
                        workspaceId,
                        monitorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_review_scope_repository_branches");
    }

    @Test
    void repositoryCoverageCannotReferenceAnotherWorkspacesMonitor() {
        long ownerWorkspace = insertWorkspace("monitor-owner-" + UUID.randomUUID());
        long targetWorkspace = insertWorkspace("monitor-target-" + UUID.randomUUID());
        Long monitorId = jdbcTemplate.queryForObject(
                "INSERT INTO repository_to_monitor (workspace_id, name_with_owner, native_id, generated_paths) VALUES (?, ?, 12345,"
                        + " '[]'::jsonb) RETURNING id",
                Long.class,
                ownerWorkspace,
                "acme/repository");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO review_scope_repository "
                                + "(workspace_id, repository_monitor_id, base_branches) VALUES (?, ?, '[]'::jsonb)",
                        targetWorkspace,
                        monitorId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sfk_review_scope_repository_monitor");
    }

    static Stream<String> invalidBaseBranches() {
        return Stream.of("\"main\"", "[1]", "[\"\"]", "[\"   \"]", "[\"" + "x".repeat(256) + "\"]");
    }

    /** A workspace row with only the columns the schema demands; every setting below is left at its default. */
    private long insertWorkspace(String slug) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO workspace (slug, display_name, status, account_type, account_login,"
                        + " public_activity_enabled) VALUES (?, ?, 'ACTIVE', 'ORG', ?, false) RETURNING id",
                Long.class,
                slug,
                "Review scope constraint fixture",
                slug);
        return Objects.requireNonNull(id, "workspace insert returned no id");
    }

    private void setScopeMode(long workspaceId, String column, String mode) {
        jdbcTemplate.update("UPDATE workspace SET " + column + " = ? WHERE id = ?", mode, workspaceId);
    }

    private void assertIndexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                Integer.class,
                indexName);
        assertThat(count)
                .as("Liquibase-built schema must contain index %s", indexName)
                .isNotNull()
                .isEqualTo(1);
    }

    private void assertConstraintExists(String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = ?", Integer.class, constraintName);
        assertThat(count).isEqualTo(1);
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                Integer.class,
                table,
                column);
        assertThat(count)
                .as("Liquibase-built schema must contain column %s.%s", table, column)
                .isNotNull()
                .isEqualTo(1);
    }

    private void assertColumnRequired(String table, String column) {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                String.class,
                table,
                column);
        assertThat(nullable).isEqualTo("NO");
    }

    @Test
    void configAuditRowsAreAppendOnlyWithTheRetentionException() {
        long workspaceId = auditWorkspaceId();
        long current = insertAuditRow(workspaceId, 0);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET entity_type = 'AGENT_CONFIG' WHERE id = ?", current))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM config_audit_event WHERE id = ?", current))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE config_audit_event"))
                .hasMessageContaining("append-only");

        assertThatCode(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET actor_account_id = NULL WHERE id = ?", current))
                .doesNotThrowAnyException();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT new_value::text FROM config_audit_event WHERE id = ?", String.class, current))
                .contains("cooldownMinutes");
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE config_audit_event SET actor_account_id = NULL, entity_type = 'AGENT_CONFIG' WHERE id"
                                + " = ?",
                        current))
                .hasMessageContaining("append-only");

        long directlyExpired = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS + 1);
        assertThatCode(() -> jdbcTemplate.update("DELETE FROM config_audit_event WHERE id = ?", directlyExpired))
                .doesNotThrowAnyException();
        long stale = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS + 1);
        long inside = insertAuditRow(workspaceId, ConfigAuditRetentionJob.RETENTION_DAYS - 1);
        retentionJob.sweep();
        assertThat(auditRowExists(stale)).isFalse();
        assertThat(auditRowExists(inside)).isTrue();
        assertThat(auditRowExists(current)).isTrue();
    }

    static Stream<Arguments> auditEnumConstraints() {
        return Stream.of(
                Arguments.of("ck_config_audit_event_entity_type", ConfigAuditEntityType.values()),
                Arguments.of("ck_config_audit_event_action", ConfigAuditAction.values()),
                Arguments.of("ck_config_audit_event_actor_kind", ConfigAuditActorKind.values()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("auditEnumConstraints")
    void auditConstraintsAcceptEveryApplicationValue(String constraintName, Enum<?>[] values) {
        String definition = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraintName);
        List<String> accepted = Arrays.stream(Objects.requireNonNull(definition).split("'"))
                .filter(part -> part.matches("[A-Z_]{2,}"))
                .distinct()
                .toList();
        assertThat(accepted)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(values).map(Enum::name).toList());
    }

    private long auditWorkspaceId() {
        return workspaceRepository
                .findByWorkspaceSlug("audit-immutability")
                .orElseGet(() -> {
                    Workspace workspace = new Workspace();
                    workspace.setWorkspaceSlug("audit-immutability");
                    workspace.setDisplayName("Immutability");
                    workspace.setAccountLogin("audit-immutability-org");
                    workspace.setAccountType(AccountType.ORG);
                    return workspaceRepository.save(workspace);
                })
                .getId();
    }

    private long insertAuditRow(long workspaceId, int ageInDays) {
        return Objects.requireNonNull(jdbcTemplate.queryForObject("""
            INSERT INTO config_audit_event
                (workspace_id, entity_type, entity_id, action, actor_kind, actor_account_id,
                 old_value, new_value, changed_keys, occurred_at)
            VALUES (?, 'PRACTICE_REVIEW_SETTINGS', '1', 'UPDATED', 'USER', NULL,
                    '{"cooldownMinutes":30}'::jsonb, '{"cooldownMinutes":10}'::jsonb,
                    ARRAY['cooldownMinutes'], now() - make_interval(days => ?))
            RETURNING id
            """, Long.class, workspaceId, ageInDays));
    }

    private boolean auditRowExists(long id) {
        Integer count =
                jdbcTemplate.queryForObject("SELECT count(*) FROM config_audit_event WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    @TestConfiguration
    static class JsonConfiguration {

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
