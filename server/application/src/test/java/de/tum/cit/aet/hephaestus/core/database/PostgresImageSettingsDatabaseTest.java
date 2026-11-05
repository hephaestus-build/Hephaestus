package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The settings that the release's PostgreSQL image starts with (docker/postgres/size-from-memory.sh), read from the
 * image these tests run on.
 */
@Tag("database")
class PostgresImageSettingsDatabaseTest {

    @Test
    void shouldCompressFullPageImagesAndPreloadStatementStatistics() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("postgres_image_settings");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SHOW wal_compression")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1))
                        .as("a streamed answer's full-page images are compressed")
                        .isEqualTo("lz4");
            }
            try (var result = statement.executeQuery("SHOW shared_preload_libraries")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).contains("pg_stat_statements");
            }
        }
    }

    @Test
    void shouldLogLockWaitsTemporaryFilesAndSlowMaintenanceButNeverAStatement() throws SQLException {
        var database = PostgreSQLTestContainer.createDatabase("postgres_image_logging");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var statement = connection.createStatement()) {
            var expected = new LinkedHashMap<String, String>();
            expected.put("log_lock_waits", "on");
            expected.put("log_temp_files", "10MB");
            expected.put("log_autovacuum_min_duration", "1s");
            expected.put("log_checkpoints", "on");
            // These would write statement text with its bound values, which is personal data.
            expected.put("log_statement", "none");
            expected.put("log_min_duration_statement", "-1");
            expected.put("log_min_error_statement", "panic");
            for (var setting : expected.entrySet()) {
                try (var result = statement.executeQuery("SHOW " + setting.getKey())) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).as(setting.getKey()).isEqualTo(setting.getValue());
                }
            }
        }
    }
}
