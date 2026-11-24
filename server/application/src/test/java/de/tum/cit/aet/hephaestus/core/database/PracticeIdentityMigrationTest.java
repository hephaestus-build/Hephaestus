package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;

/**
 * The upgrade gives each practice version of the previous release its identity (ADR 0130), and the rollback removes
 * the identity again. The rows come from the release drill's conversion seed.
 */
@Tag("database")
class PracticeIdentityMigrationTest {
    /** The name without its timestamp, which a rebase can change. */
    private static final String CHANGELOG = "-feat-domain-qualified-practice-identity.xml";

    @Test
    void shouldGiveTheSeededVersionsTheirIdentityAndRollBack() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "practice_identity_migration_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(database, resource("practice-identity.seed.sql"));

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
            PracticeMigrationFixture.execute(database, resource("practice-identity.expect.sql"));
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT convalidated FROM pg_constraint WHERE conname = 'ck_practice_version_identity'"))
                    .as("the last change set validates the check on the stored rows")
                    .containsExactly("t");

            // An insert without the new columns still fits, so fixtures written for the previous schema still apply.
            PracticeMigrationFixture.execute(database, """
                    INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                    SELECT 979006,workspace_id,practice_id,2,definition FROM practice_version WHERE id=979005;
                    """);
            assertThat(identity(database, 979006)).containsExactly((String) null);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                            UPDATE practice_version SET identity_key = 'local/verify', comparability_class = review_digest
                            WHERE id = 979006"""))
                    .as("local is reserved for the key of a workspace practice")
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database, "UPDATE practice_version SET identity_key='local/1/2' WHERE id=979003"))
                    .isInstanceOf(DataAccessException.class);

            PracticeMigrationFixture.rollbackOnly(liquibase, "prod", CHANGELOG);
        }
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_name = 'practice_version' AND column_name IN ('identity_key', 'comparability_class')
                        """)).containsExactly("0");
        // The restored baseline guard still lets erasure clear the creator, and still refuses any other change.
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT created_by_account_id FROM practice_version WHERE id=979003"))
                .containsExactly("979011");
        PracticeMigrationFixture.execute(
                database, "UPDATE practice_version SET created_by_account_id = NULL WHERE id=979003");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT created_by_account_id FROM practice_version WHERE id=979003"))
                .containsExactly((String) null);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "UPDATE practice_version SET number = 3 WHERE id=979006"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("practice versions are immutable");
    }

    /**
     * The conversion seed runs on each release an upgrade can start from: the 1.0 baseline (v1.0.0-rc.1), a later release
     * before this change set, and a release that already has it. After the upgrade its expectation holds on each.
     */
    @ParameterizedTest
    @ValueSource(strings = {"baseline", "before", "after"})
    void shouldMeetTheSeedExpectationFromEachRelease(String release) throws Exception {
        String name = "practice_identity_seed_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        switch (release) {
            case "baseline" -> {
                try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                        var liquibase = new Liquibase(
                                "db/master.xml",
                                new ClassLoaderResourceAccessor(),
                                DatabaseFactory.getInstance()
                                        .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
                    liquibase.update("1.0.0-baseline", new Contexts("prod"), new LabelExpression());
                }
            }
            case "before" -> PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
            default -> PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        }
        PracticeMigrationFixture.execute(owner, resource("practice-identity.seed.sql"));

        PostgreSQLTestContainer.migrateDatabase(owner, "prod");

        PracticeMigrationFixture.execute(owner, resource("practice-identity.expect.sql"));
    }

    private static List<@Nullable String> identity(TestDatabase database, long id) {
        return PracticeMigrationFixture.column(database, "SELECT identity_key FROM practice_version WHERE id=" + id);
    }

    private static String resource(String name) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                PracticeIdentityMigrationTest.class.getResourceAsStream("/db/cutover-seeds/" + name))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
