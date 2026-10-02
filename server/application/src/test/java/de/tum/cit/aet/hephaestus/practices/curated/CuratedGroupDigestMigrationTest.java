package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class CuratedGroupDigestMigrationTest {
    private static final String MIGRATION = "1790954438557_changelog.xml";
    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("curated_group_digest_migration");
    private static final String EARLIER = "area:v1:" + "a".repeat(64);

    @Test
    void shouldKeepEarlierGroupDigestsAndAcceptOnlyTheCurrentOneAfterMigration() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            var pending = liquibase.listUnrunChangeSets(new Contexts("prod"), new LabelExpression());
            int index = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(MIGRATION))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(index, new Contexts("prod"), new LabelExpression());
        }
        execute(
                "INSERT INTO curated_group_override(slug,name,based_on_digest,created_at,updated_at,version)"
                        + " VALUES('earlier-group','Earlier','" + EARLIER + "',now(),now(),0)",
                "INSERT INTO curated_group_override(slug,name,created_at,updated_at,version)"
                        + " VALUES('current-group','Current',now(),now(),0)");
        String current = CuratedDefinitionDigest.of("current-group", new GroupDefinition("Current", null, null, null));
        assertThatThrownBy(() -> setDigest(current)).isInstanceOfSatisfying(SQLException.class, exception -> {
            assertThat(exception.getSQLState()).isEqualTo("23514");
            assertThat(exception.getMessage()).contains("ck_curated_group_override_based_on");
        });

        migrate();

        assertThat(scalar("SELECT based_on_digest FROM curated_group_override WHERE slug='earlier-group'"))
                .isEqualTo(EARLIER);
        assertThat(
                        scalar(
                                "SELECT (based_on_digest IS NULL)::text FROM curated_group_override WHERE slug='current-group'"))
                .isEqualTo("true");
        setDigest(current);
        assertThat(scalar("SELECT based_on_digest FROM curated_group_override WHERE slug='current-group'"))
                .isEqualTo(current);
        for (String malformed : List.of(
                "group:v1:" + "a".repeat(63),
                "group:v1:" + "A".repeat(64),
                "group:v2:" + "a".repeat(64),
                "practice:v3:" + "a".repeat(64),
                "a".repeat(64),
                current + "0")) {
            assertThatThrownBy(() -> setDigest(malformed)).isInstanceOfSatisfying(SQLException.class, exception -> {
                assertThat(exception.getSQLState()).isEqualTo("23514");
                assertThat(exception.getMessage()).contains("ck_curated_group_override_based_on");
            });
        }
    }

    private static void setDigest(String digest) throws SQLException {
        execute("UPDATE curated_group_override SET based_on_digest='" + digest + "' WHERE slug='current-group'");
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                for (String sql : statements) statement.execute(sql);
                connection.commit();
            }
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return Objects.requireNonNull(rows.getString(1));
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static Liquibase liquibase() throws Exception {
        return new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect())));
    }

    private static void migrate() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            liquibase.update(new Contexts("prod"));
        }
    }
}
