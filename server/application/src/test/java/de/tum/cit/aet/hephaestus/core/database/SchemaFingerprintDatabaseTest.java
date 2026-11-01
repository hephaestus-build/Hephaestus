package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.IntStream;
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
 * The schema fingerprint ({@code db/schema-fingerprint.sql}) and the schema that the 1.0.0-rc.1 chain builds. The 1.0
 * baseline must reproduce that schema exactly, so its fingerprint is pinned here while the chain still exists.
 */
@Tag("database")
class SchemaFingerprintDatabaseTest {

    /** The last change set of 1.0.0-rc.1. */
    private static final String RC1_HEAD_FILE = "next/20261101103000-erasure-history-redaction.xml";

    private static final String RC1_HEAD_ID = "1793504565891-1";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPinTheSchemaThatTheReleaseCandidateChainBuilds(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("fingerprint_rc1_" + context);
        try (Connection connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var contexts = new Contexts(context);
            var labels = new LabelExpression();
            var pending = liquibase.listUnrunChangeSets(contexts, labels);
            int through = IntStream.range(0, pending.size())
                            .filter(index -> pending.get(index).getFilePath().endsWith(RC1_HEAD_FILE)
                                    && pending.get(index).getId().equals(RC1_HEAD_ID))
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException(RC1_HEAD_FILE + " is not in the chain"))
                    + 1;
            liquibase.update(through, contexts, labels);

            List<String> actual = SchemaFingerprint.of(connection);
            String file = "rc1-" + context + ".txt";
            List<String> pinned = pinned(file);
            if (!actual.equals(pinned)) {
                // Written for review: a change that is meant to alter the rc.1 schema does not exist.
                Path written = Path.of("build", "fingerprint", file);
                Files.createDirectories(written.getParent());
                Files.write(written, actual, StandardCharsets.UTF_8);
            }
            assertThat(actual)
                    .as(
                            "the rc.1 schema (%s); this run's fingerprint is in build/fingerprint/%s:%n%s",
                            context, file, SchemaFingerprint.difference(pinned, actual))
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

            connection.setAutoCommit(false);
            for (String change : List.of(
                    "ALTER TABLE kinds ALTER COLUMN v SET DEFAULT 'b'",
                    "ALTER TABLE kinds ALTER COLUMN v SET NOT NULL",
                    "ALTER TABLE kinds ALTER COLUMN v TYPE varchar(20)",
                    "ALTER TABLE kinds ADD COLUMN w integer",
                    "ALTER TABLE kinds ADD CONSTRAINT kinds_v_set CHECK (v <> '')",
                    "DROP INDEX kinds_v",
                    "ALTER SEQUENCE kinds_seq INCREMENT BY 2",
                    "CREATE OR REPLACE VIEW kinds_view AS SELECT id FROM kinds WHERE id > 0",
                    "CREATE OR REPLACE FUNCTION kinds_touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
                            + " NEW.v = 'x'; RETURN NEW; END $$",
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

    private static List<String> pinned(String file) throws IOException {
        try (InputStream in = SchemaFingerprintDatabaseTest.class.getResourceAsStream("/db/fingerprint/" + file)) {
            return in == null
                    ? List.of()
                    : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .toList();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
