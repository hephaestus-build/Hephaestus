package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("database")
class RepositoryObservationStoreDatabaseTest {
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void createFixtures() {
        var db = PostgreSQLTestContainer.createApplicationDatabase("repository_observations");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password()));
        jdbc.execute("""
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979601, 'observation-a', 'Observation A', 'test', 'ORG', 'ACTIVE'),
                       (979602, 'observation-b', 'Observation B', 'test', 'ORG', 'ACTIVE');
                INSERT INTO connection(id, workspace_id, kind, instance_key, state, config)
                VALUES (979601, 979601, 'GITLAB', '979601', 'ACTIVE',
                        '{"type":"GITLAB","serverUrl":"https://gitlab.com","gitlabGroupId":979601}'),
                       (979602, 979602, 'GITLAB', '979602', 'ACTIVE',
                        '{"type":"GITLAB","serverUrl":"https://gitlab.com","gitlabGroupId":979602}');
                """);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            PUBLIC, true, PUBLIC, PUBLIC, PUBLIC, true
            PUBLIC, true, CLOSED, PUBLIC, CLOSED, true
            PUBLIC, true, UNAVAILABLE, PUBLIC, UNAVAILABLE, true
            PUBLIC, true, UNMONITORED, PUBLIC, UNMONITORED, true
            PUBLIC, false, PUBLIC, CLOSED, PUBLIC, false
            PUBLIC, false, CLOSED, CLOSED, CLOSED, false
            PUBLIC, false, UNAVAILABLE, CLOSED, UNAVAILABLE, false
            PUBLIC, false, UNMONITORED, CLOSED, UNMONITORED, false
            PRIVATE, true, PUBLIC, CLOSED, CLOSED, true
            PRIVATE, true, CLOSED, CLOSED, CLOSED, true
            PRIVATE, true, UNAVAILABLE, CLOSED, UNAVAILABLE, true
            PRIVATE, true, UNMONITORED, CLOSED, UNMONITORED, true
            PRIVATE, false, PUBLIC, CLOSED, CLOSED, false
            PRIVATE, false, CLOSED, CLOSED, CLOSED, false
            PRIVATE, false, UNAVAILABLE, CLOSED, UNAVAILABLE, false
            PRIVATE, false, UNMONITORED, CLOSED, UNMONITORED, false
            NOT_FOUND, true, PUBLIC, UNAVAILABLE, PUBLIC, false
            NOT_FOUND, true, CLOSED, UNAVAILABLE, CLOSED, false
            NOT_FOUND, true, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, false
            NOT_FOUND, true, UNMONITORED, UNAVAILABLE, UNMONITORED, false
            NOT_FOUND, false, PUBLIC, UNAVAILABLE, PUBLIC, false
            NOT_FOUND, false, CLOSED, UNAVAILABLE, CLOSED, false
            NOT_FOUND, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, false
            NOT_FOUND, false, UNMONITORED, UNAVAILABLE, UNMONITORED, false
            METADATA, true, PUBLIC, CLOSED, PUBLIC, true
            METADATA, true, CLOSED, CLOSED, CLOSED, true
            METADATA, true, UNAVAILABLE, CLOSED, UNAVAILABLE, true
            METADATA, true, UNMONITORED, CLOSED, UNMONITORED, true
            METADATA, false, PUBLIC, CLOSED, PUBLIC, false
            METADATA, false, CLOSED, CLOSED, CLOSED, false
            METADATA, false, UNAVAILABLE, CLOSED, UNAVAILABLE, false
            METADATA, false, UNMONITORED, CLOSED, UNMONITORED, false
            """)
    void appliesStateTable(
            String observation, boolean current, String beforeB, String afterA, String afterB, boolean writeMetadata) {
        resetState(beforeB);

        jdbc.queryForObject(
                """
                SELECT public.store_repository_observation(
                    979601, 979601, 0, 0, id, 979619, 979621, ?, '2021-01-01'::timestamptz, ?, ?,
                    '{"name_with_owner":"test/new","name":"new","html_url":"https://gitlab.com/test/new",
                      "default_branch":"release"}')
                FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                """,
                Long.class,
                current ? 4L : 3L,
                observation,
                switch (observation) {
                    case "PUBLIC", "PRIVATE" -> observation;
                    default -> null;
                });

        assertThat(accessState(979621)).isEqualTo(afterA);
        assertThat(accessState(979622)).isEqualTo(afterB);
        assertThat(jdbc.queryForObject("""
                SELECT public_observation_at IS NOT NULL OR repository_visibility_confirmed_at IS NOT NULL
                FROM repository_to_monitor WHERE id = 979621
                """, Boolean.class)).isEqualTo(current && observation.equals("PUBLIC"));
        assertThat(jdbc.queryForObject("SELECT name_with_owner FROM repository WHERE id = 979611", String.class))
                .isEqualTo(writeMetadata ? "test/new" : "test/old");
        assertThat(jdbc.queryForObject("SELECT default_branch FROM repository WHERE id = 979611", String.class))
                .isEqualTo(writeMetadata ? "release" : "main");
    }

    @Test
    void olderPrivateReadCannotRestoreMetadataWrittenByAnotherWorkspace() {
        resetState("PUBLIC");
        var rename = jdbc.queryForObject("""
                SELECT public.store_repository_observation(
                    979602, 979602, 0, 0, id, 979619, 979622, 0, '2022-01-01'::timestamptz, 'METADATA', NULL,
                    '{"name_with_owner":"test/renamed","name":"renamed","html_url":"https://gitlab.com/test/renamed"}')
                FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                """, Long.class);
        assertThat(rename).isEqualTo(979611L);
        var old = jdbc.queryForObject("""
                SELECT public.store_repository_observation(
                    979601, 979601, 0, 0, id, 979619, 979621, 4, '2021-01-01'::timestamptz, 'PRIVATE', 'PRIVATE',
                    '{"name_with_owner":"test/old","name":"old","html_url":"https://gitlab.com/test/old"}')
                FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                """, Long.class);
        assertThat(old).isEqualTo(-1L);
        assertThat(accessState(979621)).isEqualTo("CLOSED");
        assertThat(accessState(979622)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT name_with_owner FROM repository WHERE id = 979611", String.class))
                .isEqualTo("test/renamed");
    }

    @Test
    void rejectsTheReadOfAReplacedCredential() {
        resetState("PUBLIC");
        jdbc.update("UPDATE connection SET credentials_generation = 1 WHERE id = 979601");
        try {
            var old = jdbc.queryForObject("""
                    SELECT public.store_repository_observation(
                        979601, 979601, 0, 0, id, 979619, 979621, 5, '2021-01-01'::timestamptz, 'PUBLIC', 'PUBLIC',
                        '{}'::jsonb)
                    FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                    """, Long.class);
            assertThat(old).isEqualTo(-1L);
            assertThat(accessState(979621)).isEqualTo("CLOSED");
            assertThat(accessState(979622)).isEqualTo("PUBLIC");
        } finally {
            jdbc.update("UPDATE connection SET credentials_generation = 0 WHERE id = 979601");
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false"})
    void queuedPrivateSnapshotPreservesScopedLoss(boolean receivedAfterLoss, boolean recapturedGeneration) {
        resetState("PUBLIC");
        var beforeLoss = databaseTime();
        jdbc.queryForObject("""
                SELECT public.store_repository_observation(
                    979601, 979601, 0, 0, id, 979619, 979621, 4, CAST(? AS timestamptz), 'NOT_FOUND', NULL, NULL)
                FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                """, Long.class, beforeLoss.toString());
        var loss = jdbc.queryForMap("""
                SELECT unavailable_since, unavailable_retry_at FROM repository_to_monitor WHERE id = 979621
                """);
        var receivedAt = receivedAfterLoss ? databaseTime() : beforeLoss;

        var result = jdbc.queryForObject(
                """
                SELECT public.store_repository_observation(
                    979601, 979601, 0, 0, id, 979619, CAST(? AS bigint), ?, CAST(? AS timestamptz), 'PRIVATE', 'PRIVATE',
                    '{"name_with_owner":"test/new","name":"new","html_url":"https://gitlab.com/test/new"}')
                FROM identity_provider WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com'
                """,
                Long.class,
                recapturedGeneration ? 979621L : null,
                recapturedGeneration ? 5L : -1L,
                receivedAt.toString());

        assertThat(result).isEqualTo(receivedAfterLoss ? 979611L : -1L);
        assertThat(accessState(979621)).isEqualTo("UNAVAILABLE");
        assertThat(accessState(979622)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForMap("""
                SELECT unavailable_since, unavailable_retry_at FROM repository_to_monitor WHERE id = 979621
                """)).isEqualTo(loss);
        assertThat(jdbc.queryForObject("SELECT name_with_owner FROM repository WHERE id = 979611", String.class))
                .isEqualTo(receivedAfterLoss ? "test/new" : "test/old");
    }

    private static Instant databaseTime() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT clock_timestamp()", (row, number) -> row.getTimestamp(1).toInstant()));
    }

    private static void resetState(String beforeB) {
        jdbc.execute("""
                DELETE FROM repository_to_monitor WHERE workspace_id IN (979601, 979602);
                DELETE FROM repository WHERE id = 979611;
                INSERT INTO repository(id, provider_id, native_id, name, name_with_owner, html_url,
                    is_private, is_archived, is_disabled, visibility, metadata_observed_at, default_branch)
                SELECT 979611, id, 979619, 'old', 'test/old', 'https://gitlab.com/test/old',
                    false, false, false, 'PUBLIC', '2020-01-01', 'main' FROM identity_provider
                WHERE type = 'GITLAB' AND server_url = 'https://gitlab.com';
                INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner,
                    native_id, proof_generation, proof_generation_at)
                VALUES (979621, 979601, '[]', 'test/old', 979619, 4, '2020-01-01');
                """);
        if (!beforeB.equals("UNMONITORED")) {
            jdbc.update("""
                    INSERT INTO repository_to_monitor(id, workspace_id, generated_paths, name_with_owner,
                        native_id, repository_visibility_confirmed_at, public_observation_at, unavailable_since,
                        proof_generation_at)
                    VALUES (979622, 979602, '[]', 'test/old', 979619,
                            CASE WHEN ? = 'PUBLIC' THEN statement_timestamp() END,
                            CASE WHEN ? = 'PUBLIC' THEN statement_timestamp() END,
                            CASE WHEN ? = 'UNAVAILABLE' THEN statement_timestamp() END, '2020-01-01')
                    """, beforeB, beforeB, beforeB);
        }
    }

    private String accessState(long monitor) {
        return jdbc.queryForList("""
                SELECT CASE
                    WHEN m.unavailable_since IS NOT NULL THEN 'UNAVAILABLE'
                    WHEN m.repository_visibility_confirmed_at = m.public_observation_at AND r.visibility = 'PUBLIC'
                         AND NOT r.is_private THEN 'PUBLIC'
                    ELSE 'CLOSED'
                END
                FROM repository_to_monitor AS m JOIN repository AS r ON r.id = 979611
                WHERE m.id = ?
                """, String.class, monitor).stream()
                .findFirst()
                .orElse("UNMONITORED");
    }
}
