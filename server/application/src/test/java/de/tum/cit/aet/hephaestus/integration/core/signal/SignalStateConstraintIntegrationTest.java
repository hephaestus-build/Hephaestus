package de.tum.cit.aet.hephaestus.integration.core.signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import de.tum.cit.aet.hephaestus.testconfig.SchemaRowSeeder;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@Tag("database")
class SignalStateConstraintIntegrationTest {

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createMigratedDatabase("hephaestus_signal_state_constraint");

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new SingleConnectionDataSource(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password(), true));
    private final SchemaRowSeeder seeder = new SchemaRowSeeder(jdbcTemplate);

    @Test
    void shouldAdmitEverySignalStateAndRejectAnUnknownOneAfterMigration() {
        // Seeding without the workspace this row points at: session_replication_role suspends foreign
        // keys and triggers, and leaves CHECK constraints — which are what this test is about — enforced.
        jdbcTemplate.execute("SET session_replication_role = 'replica'");
        for (SignalState state : SignalState.values()) {
            seeder.insert("artifact_signal", signalRow(state.name()));
        }

        assertThatThrownBy(() -> seeder.insert("artifact_signal", signalRow("NOT_A_STATE")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_artifact_signal_state");

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM artifact_signal", Long.class))
                .as("one row per declared state and nothing more")
                .isEqualTo(SignalState.values().length);
    }

    /**
     * Explicit where the seeder's filler would violate a neighbouring constraint: {@code artifact_kind}
     * and {@code discovered_via} carry CHECKs of their own, and {@code revision} keeps the rows distinct
     * under {@code uq_artifact_signal}.
     */
    private static Map<String, Object> signalRow(String state) {
        return Map.of(
                "artifact_kind",
                "scm.issue",
                "discovered_via",
                "EVENT",
                "signal_name",
                "scm.issue.updated",
                "revision",
                state,
                "state",
                state);
    }
}
