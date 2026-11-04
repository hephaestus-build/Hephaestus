package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Column type rules for the migrated schema. */
class SchemaColumnTypeIntegrationTest extends BaseIntegrationTest {

    /**
     * A timestamp without a time zone means what the reading session's time zone says, and the server's JVM runs in
     * Europe/Berlin. Liquibase and ShedLock own their tables.
     */
    private static final String TIMESTAMPS_WITHOUT_TIME_ZONE = """
            SELECT table_name || '.' || column_name FROM information_schema.columns
            WHERE table_schema = current_schema() AND data_type = 'timestamp without time zone'
              AND table_name NOT IN ('databasechangelog', 'databasechangeloglock', 'shedlock')
            ORDER BY 1
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldStoreEveryTimestampWithItsTimeZone() {
        assertThat(jdbc.queryForList(TIMESTAMPS_WITHOUT_TIME_ZONE, String.class))
                .as("Store these columns as timestamp with time zone")
                .isEmpty();
    }
}
