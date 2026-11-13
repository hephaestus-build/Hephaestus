package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class ProviderResourceRepairMigrationIntegrationTest {
    private static final String CHANGELOG = "20261113151121-feat-provider-resource-adoption-fence.xml";
    private static final String VALIDATION =
            "SELECT convalidated::int::text FROM pg_constraint WHERE conrelid IN ('public.auth_event'::regclass, 'public.config_audit_event'::regclass) AND conname IN ('ck_config_audit_event_entity_type', 'ck_config_audit_event_scope', 'ck_auth_event_event_type') ORDER BY conname";

    @Test
    void shouldSeparateTheAuditConstraintScanFromItsExclusiveLock() throws Exception {
        TestDatabase database = beforeUpgrade();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(6, new Contexts("prod"), new LabelExpression());
            assertThat(PracticeMigrationFixture.column(database, VALIDATION)).containsExactly("0", "0", "0");
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            assertThat(PracticeMigrationFixture.column(database, VALIDATION)).containsExactly("1", "1", "1");
        }
    }

    @Test
    void shouldRefuseRollbackAfterAReleaseWithoutDeletingItsAuditEvidence() throws Exception {
        TestDatabase database = beforeUpgrade();
        String release = UUID.randomUUID().toString();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            PracticeMigrationFixture.execute(
                    database,
                    "INSERT INTO config_audit_event (occurred_at, actor_kind, entity_type, entity_id, action, changed_keys, new_value) VALUES (clock_timestamp(), 'SYSTEM', 'PROVIDER_RESOURCE_RELEASE', '"
                            + release + "', 'UPDATED', ARRAY['confirmedGone'], '{\"confirmedGone\":true}')");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasStackTraceContaining("Provider resource release audit evidence");
        }
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT new_value FROM config_audit_event WHERE entity_id = '" + release + "'"))
                .containsExactly("{\"confirmedGone\":true}");
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT column_name FROM information_schema.columns WHERE table_name = 'provider_resource_repair' AND column_name = 'observed_token_fingerprint'"))
                .containsExactly("observed_token_fingerprint");
    }

    @Test
    void shouldRetainUnresolvedRepairIdentityWhenRollbackIsRequested() throws Exception {
        TestDatabase database = beforeUpgrade();
        String identity = UUID.randomUUID().toString();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            PracticeMigrationFixture.execute(
                    database,
                    "INSERT INTO provider_resource_repair (id, identity_hash, kind, origin, resource_type, parent_id, resource_id, state, created_at, updated_at, next_attempt_at, version, attempt_epoch, removal_attempts) VALUES ('"
                            + identity + "', '" + "a".repeat(64)
                            + "', 'GITHUB', 'https://github.com', 'INSTALLATION', '77', '42', 'CONFIRMATION_REQUIRED', clock_timestamp(), clock_timestamp(), clock_timestamp(), 0, 0, 1)");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG))
                    .hasStackTraceContaining("unresolved repairs");
        }
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT resource_id FROM provider_resource_repair WHERE id = '" + identity + "'"))
                .containsExactly("42");
    }

    @Test
    void shouldRestoreValidatedAuditConstraintsAfterAnUnusedMigrationIsRolledBack() throws Exception {
        TestDatabase database = beforeUpgrade();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);
        }
        assertThat(PracticeMigrationFixture.column(database, VALIDATION)).containsExactly("1", "1", "1");
    }

    private TestDatabase beforeUpgrade() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "repair_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        return database;
    }
}
