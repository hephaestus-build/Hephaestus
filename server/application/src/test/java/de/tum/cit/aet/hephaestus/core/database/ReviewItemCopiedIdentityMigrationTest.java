package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The copied identities of earlier review items move from their bounded detail into their own rows. */
@Tag("database")
class ReviewItemCopiedIdentityMigrationTest {
    private static final String CHANGELOG = "-fix-copied-identities.xml";
    private static final String CONVERSION = "'1795629966150-5'";

    /**
     * The conversion seed runs on each release an upgrade can start from: the 1.0 baseline (v1.0.0-rc.1), a later release
     * before this change set, and a release that already has it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"baseline", "before", "after"})
    void shouldMoveEachMembersDistinctCopiedIdentitiesIntoTheirTable(String release) throws Exception {
        String name = "copied_identity_seed_" + UUID.randomUUID().toString().replace("-", "");
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
        PracticeMigrationFixture.execute(owner, seed("seed"));

        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        // A second run of the conversion, as after a run that stopped before Liquibase recorded it, changes nothing.
        PracticeMigrationFixture.execute(owner, "DELETE FROM databasechangelog WHERE id IN (" + CONVERSION + ")");
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");

        PracticeMigrationFixture.execute(owner, seed("expect"));
    }

    /**
     * The previous release froze an admitted erasure's selection while copied identities were part of the detail. The
     * upgrade stops and names it, so no erasure resumes across the move. Without it, the upgrade runs.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ERASING", "FAILED"})
    void shouldStopTheUpgradeWhileAnErasureIsAdmittedAndNotComplete(String state) throws Exception {
        String name = "copied_identity_drain_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        var admitted = UUID.randomUUID();
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO person_data_request(id,state,counts_json,completed_json,created_at,expires_at,version)
                VALUES('%s','%s','{}','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '2 days',0);
                """.formatted(admitted, state));

        assertThatThrownBy(() -> PostgreSQLTestContainer.migrateDatabase(owner, "prod"))
                .hasStackTraceContaining(admitted + " (" + state + ")");
        assertThat(
                        PracticeMigrationFixture.column(
                                owner,
                                "SELECT count(*) FROM information_schema.tables WHERE table_name='review_item_copied_identity'"))
                .containsExactly("0");

        // Completed on the previous release, or never admitted: the request no longer holds the upgrade.
        PracticeMigrationFixture.execute(owner, "DELETE FROM person_data_request WHERE id='" + admitted + "'");
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
    }

    private static String seed(String part) throws Exception {
        try (var input = Objects.requireNonNull(ReviewItemCopiedIdentityMigrationTest.class.getResourceAsStream(
                "/db/cutover-seeds/review-item-copied-identity." + part + ".sql"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
