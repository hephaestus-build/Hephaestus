package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Tag("database")
class ScanUsageStopReasonDatabaseTest {
    private static final String REVIEW = "00000000-0000-0000-0000-000000978011";
    private static final String CHANGELOG = "1791486276211_changelog.xml";
    private static final String CHANGE_SET = "1791486276211-1";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldPreserveReviewsAndPermitTheUsageReasonWhenTheConstraintIsUpgraded(String context) throws Exception {
        // Later changelogs may refuse rollback, so the database stops forward before the change under test.
        var database = PostgreSQLTestContainer.createDatabase(
                "scan_usage_" + context + "_" + UUID.randomUUID().toString().replace("-", ""));
        try (var liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int prefix = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getId().equals(CHANGE_SET)
                            && pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(prefix, new Contexts(context), new LabelExpression());
        }
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        PracticeMigrationFixture.execute(database, """
            UPDATE review SET status='PARTIAL',stop_reason='BUDGET',completed_at=now()
            WHERE id='00000000-0000-0000-0000-000000978011'
            """);
        var before = PracticeMigrationFixture.column(
                database, "SELECT to_jsonb(r)::text FROM review r WHERE id='" + REVIEW + "'");
        assertThatThrownBy(() -> reason(database, "USAGE_UNVERIFIABLE")).hasRootCauseInstanceOf(SQLException.class);

        try (var liquibase = liquibase(database)) {
            liquibase.update(1, new Contexts(context), new LabelExpression());
        }
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 1"))
                .containsExactly(CHANGE_SET);

        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT to_jsonb(r)::text FROM review r WHERE id='" + REVIEW + "'"))
                .isEqualTo(before);
        reason(database, "USAGE_UNVERIFIABLE");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT stop_reason FROM review WHERE id='" + REVIEW + "'"))
                .containsExactly("USAGE_UNVERIFIABLE");
        assertThatThrownBy(() -> reason(database, "UNKNOWN_USAGE_REASON")).hasRootCauseInstanceOf(SQLException.class);

        try (var liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(context), new LabelExpression()))
                    .hasMessageContaining("Cannot remove a stop reason used by existing reviews");
        }
        reason(database, "BUDGET");
        try (var liquibase = liquibase(database)) {
            liquibase.rollback(1, new Contexts(context), new LabelExpression());
        }
        assertThatThrownBy(() -> reason(database, "USAGE_UNVERIFIABLE")).hasRootCauseInstanceOf(SQLException.class);
        reason(database, "EXECUTION_FAILED");
    }

    private static void reason(TestDatabase database, String reason) {
        PracticeMigrationFixture.execute(
                database, "UPDATE review SET stop_reason='" + reason + "' WHERE id='" + REVIEW + "'");
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }
}
