package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The schema fingerprint ({@code db/schema-fingerprint.sql}) and the schema of the 1.0 baseline. The pins came from
 * databases that the published 1.0.0-rc.1 image built, so the baseline equals that release for as long as they match.
 */
@Tag("database")
class SchemaFingerprintDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPinTheSchemaThatTheBaselineBuilds(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("fingerprint_baseline_" + context);
        try (Connection connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update("1.0.0-baseline", new Contexts(context), new LabelExpression());

            List<String> actual = SchemaFingerprint.of(connection);
            List<String> pinned = pinned("baseline-" + context + ".txt");
            assertThat(actual)
                    .as("the baseline's schema (%s):%n%s", context, SchemaFingerprint.difference(pinned, actual))
                    .isEqualTo(pinned);
        }
    }

    @Test
    void shouldChangeWithEachKindOfObjectThatAChangelogDecides() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("fingerprint_kinds");
        try (Connection connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            execute(connection, """
                    CREATE SCHEMA partman;
                    CREATE EXTENSION pg_partman WITH SCHEMA partman;
                    CREATE EXTENSION citext;
                    CREATE TABLE kinds (id bigint PRIMARY KEY, v text DEFAULT 'a');
                    CREATE INDEX kinds_v ON kinds (v);
                    CREATE SEQUENCE kinds_seq;
                    CREATE VIEW kinds_view AS SELECT id FROM kinds;
                    CREATE FUNCTION kinds_touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                    CREATE TRIGGER kinds_touch BEFORE UPDATE ON kinds FOR EACH ROW EXECUTE FUNCTION kinds_touch();
                    CREATE TABLE events (id bigint, at timestamptz NOT NULL) PARTITION BY RANGE (at);
                    CREATE TABLE databasechangelog (id text);
                    """);
            List<String> base = SchemaFingerprint.of(connection);
            assertThat(base).anyMatch(line -> line.startsWith("extension|pg_partman|partman|"));
            assertThat(base).contains("partition|events|RANGE (at)");
            assertThat(base)
                    .as("an extension's functions move with its minor version, which the extension line leaves out")
                    .noneMatch(line -> line.startsWith("function|citext"));

            connection.setAutoCommit(false);
            for (String change : List.of(
                    "ALTER TABLE kinds ALTER COLUMN v SET DEFAULT 'b'",
                    "ALTER TABLE kinds ALTER COLUMN v SET NOT NULL",
                    "ALTER TABLE kinds ALTER COLUMN v TYPE varchar(20)",
                    "ALTER TABLE kinds ADD COLUMN w integer",
                    "ALTER TABLE kinds ADD CONSTRAINT kinds_v_set CHECK (v <> '')",
                    "DROP INDEX kinds_v",
                    "ALTER SEQUENCE kinds_seq INCREMENT BY 2",
                    "ALTER TABLE kinds SET (fillfactor = 50)",
                    "CREATE OR REPLACE VIEW kinds_view AS SELECT id FROM kinds WHERE id > 0",
                    "CREATE OR REPLACE FUNCTION kinds_touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                            + " NEW.v = 'x'; RETURN NEW; END $$",
                    "CREATE OR REPLACE FUNCTION kinds_touch() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER AS $$"
                            + " BEGIN RETURN NEW; END $$",
                    "ALTER TABLE kinds DISABLE TRIGGER kinds_touch",
                    "DROP TRIGGER kinds_touch ON kinds")) {
                execute(connection, change);
                assertThat(SchemaFingerprint.of(connection)).as(change).isNotEqualTo(base);
                connection.rollback();
            }

            execute(connection, """
                    INSERT INTO kinds (id) VALUES (1);
                    SELECT nextval('kinds_seq');
                    CREATE TABLE events_2026 PARTITION OF events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
                    ALTER TABLE databasechangelog ADD COLUMN author text;
                    """);
            assertThat(SchemaFingerprint.of(connection))
                    .as("rows, sequence positions, runtime partitions and Liquibase's own tables are not the schema")
                    .isEqualTo(base);
            connection.rollback();
        }
    }

    @Test
    void shouldNotChangeWithTheFormThatARestoreLeavesOrWithWhatAnExtensionOwns() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("fingerprint_forms");
        try (Connection connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            execute(connection, """
                    CREATE SCHEMA partman;
                    CREATE EXTENSION pg_partman WITH SCHEMA partman;
                    CREATE TABLE kinds (
                        v varchar(8) CONSTRAINT old_name NOT NULL,
                        n integer,
                        CONSTRAINT kinds_v CHECK (v::text = ANY ((ARRAY['a'::varchar, 'b'::varchar])::text[])),
                        CONSTRAINT kinds_n CHECK (n BETWEEN 1 AND 9 AND n <> 5));
                    """);
            List<String> base = SchemaFingerprint.of(connection);

            execute(connection, """
                    ALTER TABLE kinds DROP CONSTRAINT kinds_v;
                    ALTER TABLE kinds ADD CONSTRAINT kinds_v
                        CHECK ((v)::text = ANY (ARRAY[('a'::varchar)::text, ('b'::varchar)::text]));
                    ALTER TABLE kinds RENAME CONSTRAINT old_name TO new_name;
                    ALTER TABLE kinds DROP CONSTRAINT kinds_n;
                    ALTER TABLE kinds ADD CONSTRAINT kinds_n CHECK ((n >= 1) AND (n <= 9) AND (n <> 5));
                    CREATE EXTENSION pg_trgm;
                    """);
            assertThat(SchemaFingerprint.of(connection))
                    .as("a restored expression, a renamed not-null constraint and what an extension owns")
                    .filteredOn(line -> !line.startsWith("extension|"))
                    .isEqualTo(base.stream()
                            .filter(line -> !line.startsWith("extension|"))
                            .toList());

            execute(connection, """
                    ALTER TABLE kinds DROP CONSTRAINT kinds_v;
                    ALTER TABLE kinds ADD CONSTRAINT kinds_v
                        CHECK ((v)::text = ANY (ARRAY[('a'::varchar)::text, ('c'::varchar)::text]));
                    """);
            assertThat(SchemaFingerprint.of(connection).stream()
                            .filter(line -> !line.startsWith("extension|"))
                            .toList())
                    .as("a different list")
                    .isNotEqualTo(base.stream()
                            .filter(line -> !line.startsWith("extension|"))
                            .toList());
        }
    }

    private static List<String> pinned(String file) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                SchemaFingerprintDatabaseTest.class.getResourceAsStream("/db/fingerprint/" + file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
