package de.tum.cit.aet.hephaestus.agent.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class ClaimReservationRetirementIntegrationTest {
    @Test
    void shouldRetireOnlyUnusedClaimsAndPreserveCallDebitsAndGrantHistory() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("claim_reservation_retirement");
        var jdbc = new JdbcTemplate(
                new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            // Reconstruct the pre-cutover chain, before the production entry boundary is marked complete.
            liquibase
                    .getDatabaseChangeLog()
                    .getPreconditions()
                    .getNestedPreconditions()
                    .clear();
            var contexts = new Contexts("prod");
            var labels = new LabelExpression();
            var pending = liquibase.listUnrunChangeSets(contexts, labels);
            int before = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getId().equals("1793443331093-1"))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, contexts, labels);
            jdbc.update("""
                    INSERT INTO llm_call_pool(id, slug, display_name, created_at, max_concurrent_calls)
                    VALUES (1, 'retirement', 'Retirement fixture', clock_timestamp(), 4)
                    """);
            UUID claim = permit(jdbc, "CLAIM_WAITING", 0, 0);
            UUID reserved = permit(jdbc, "RESERVED", 1, 100);
            UUID active = permit(jdbc, "ACTIVE", 2, 200);
            UUID waiting = permit(jdbc, "WAITING", 0, 0);
            UUID closed = permit(jdbc, "CLOSED", 1, 50);
            Map<String, Object> reservation = row(jdbc, reserved);
            var actualCalls = List.of(row(jdbc, active), row(jdbc, waiting), row(jdbc, closed));

            liquibase.update(1, contexts, labels);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM databasechangelog WHERE id='1793443331093-1'", Long.class))
                    .isEqualTo(1L);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_call_permit WHERE id=?", Long.class, claim))
                    .isZero();
            var retired = row(jdbc, reserved);
            assertThat(retired)
                    .containsEntry("state", "CLOSED")
                    .containsEntry("requests", 0)
                    .containsEntry("tokens", 0L);
            assertThat(retired.get("closed_at")).isNotNull();
            assertThat(retired.get("granted_at")).isEqualTo(reservation.get("granted_at"));
            assertThat(retired.get("acquired_at")).isEqualTo(reservation.get("acquired_at"));
            assertThat(retired.get("renewed_at")).isEqualTo(reservation.get("renewed_at"));
            assertThat(List.of(row(jdbc, active), row(jdbc, waiting), row(jdbc, closed)))
                    .isEqualTo(actualCalls);
        }
    }

    private static UUID permit(JdbcTemplate jdbc, String state, int requests, long tokens) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO llm_call_permit(id, pool_id, worker_id, workspace_id, source_id, attempt, call_class,
                    state, requests, tokens, acquired_at, renewed_at, granted_at, closed_at)
                VALUES (?, 1, 'stopped-worker', 2, ?, 0, 'REQUEST', ?, ?, ?,
                    clock_timestamp()-interval '2 minutes', clock_timestamp()-interval '10 seconds',
                    CASE WHEN ?>0 THEN clock_timestamp()-interval '1 minute' END,
                    CASE WHEN ?='CLOSED' THEN clock_timestamp()-interval '5 seconds' END)
                """, id, UUID.randomUUID(), state, requests, tokens, requests, state);
        return id;
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, UUID id) {
        return Objects.requireNonNull(jdbc.queryForMap("SELECT * FROM llm_call_permit WHERE id=?", id));
    }
}
