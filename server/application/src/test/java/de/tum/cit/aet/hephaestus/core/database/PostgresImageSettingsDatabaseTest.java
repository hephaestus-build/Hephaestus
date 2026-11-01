package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.sql.SQLException;
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
}
