package de.tum.cit.aet.hephaestus.integration.schema;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Exercises the ADR-0022 assessment backfill SQL against its historical input schema; current-schema coverage lives in ObservationAssessmentMigrationTest. */
@Tag("database")
class ObservationAssessmentBackfillIntegrationTest {

    private static final TestDatabase DATABASE =
            PostgreSQLTestContainer.createDatabase("hephaestus_assessment_backfill");

    private final JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new SingleConnectionDataSource(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password(), true));

    /**
     * The four backfill UPDATE statements, copied VERBATIM from changeSet {@code 1781092589259-60}
     * in {@code db/changelog/1781092589259_changelog.xml}. Do not paraphrase: the whole point is to
     * assert the exact migration SQL.
     */
    private static final List<String> BACKFILL_SQL = List.of(
            "UPDATE observation o SET assessment = 'GOOD' "
                    + "FROM practice p WHERE o.practice_id = p.id AND o.presence = 'PRESENT' AND p.polarity = 'DESIRABLE'",
            "UPDATE observation o SET assessment = 'BAD' "
                    + "FROM practice p WHERE o.practice_id = p.id AND o.presence = 'ABSENT' AND p.polarity = 'DESIRABLE'",
            "UPDATE observation o SET assessment = 'BAD' "
                    + "FROM practice p WHERE o.practice_id = p.id AND o.presence = 'PRESENT' AND p.polarity = 'UNDESIRABLE'",
            "UPDATE observation o SET assessment = 'GOOD' "
                    + "FROM practice p WHERE o.practice_id = p.id AND o.presence = 'ABSENT' AND p.polarity = 'UNDESIRABLE'");

    @Test
    @DisplayName(
            "ADR-0022 backfill derives observation.assessment from practice.polarity × presence (NOT_APPLICABLE ⇒ NULL)")
    void assessmentBackfillProducesTheFourQuadrantMatrix() {
        // This archived migration used assessment as the verdict. Its input schema is deliberately
        // isolated from the current target-assessment constraints.
        jdbcTemplate.execute("CREATE TABLE practice (id BIGINT PRIMARY KEY, polarity VARCHAR(16) NOT NULL)");
        jdbcTemplate.execute(
                "CREATE TABLE observation (id UUID PRIMARY KEY, practice_id BIGINT REFERENCES practice(id), "
                        + "presence VARCHAR(32) NOT NULL, assessment VARCHAR(8))");

        long desirablePracticeId = 9_000_001L;
        long undesirablePracticeId = 9_000_002L;
        jdbcTemplate.update("INSERT INTO practice VALUES (?, 'DESIRABLE')", desirablePracticeId);
        jdbcTemplate.update("INSERT INTO practice VALUES (?, 'UNDESIRABLE')", undesirablePracticeId);

        // Six observations: every (polarity, presence) pair, all with assessment NULL pre-backfill.
        UUID desPresent = seedObservation(desirablePracticeId, "PRESENT");
        UUID desAbsent = seedObservation(desirablePracticeId, "ABSENT");
        UUID desNa = seedObservation(desirablePracticeId, "NOT_APPLICABLE");
        UUID undPresent = seedObservation(undesirablePracticeId, "PRESENT");
        UUID undAbsent = seedObservation(undesirablePracticeId, "ABSENT");
        UUID undNa = seedObservation(undesirablePracticeId, "NOT_APPLICABLE");

        // Precondition: every seeded row starts with a NULL assessment.
        assertThat(assessmentOf(desPresent)).isNull();
        assertThat(assessmentOf(desAbsent)).isNull();
        assertThat(assessmentOf(desNa)).isNull();
        assertThat(assessmentOf(undPresent)).isNull();
        assertThat(assessmentOf(undAbsent)).isNull();
        assertThat(assessmentOf(undNa)).isNull();

        // --- Run the migration SQL verbatim -------------------------------------------------------
        BACKFILL_SQL.forEach(jdbcTemplate::execute);

        // --- Assert the full four-quadrant matrix + NOT_APPLICABLE ⇒ NULL -------------------------
        assertThat(assessmentOf(desPresent)).as("DESIRABLE + PRESENT ⇒ GOOD").isEqualTo("GOOD");
        assertThat(assessmentOf(desAbsent)).as("DESIRABLE + ABSENT ⇒ BAD").isEqualTo("BAD");
        assertThat(assessmentOf(undPresent)).as("UNDESIRABLE + PRESENT ⇒ BAD").isEqualTo("BAD");
        assertThat(assessmentOf(undAbsent)).as("UNDESIRABLE + ABSENT ⇒ GOOD").isEqualTo("GOOD");

        assertThat(assessmentOf(desNa))
                .as("DESIRABLE + NOT_APPLICABLE ⇒ NULL (no valence)")
                .isNull();
        assertThat(assessmentOf(undNa))
                .as("UNDESIRABLE + NOT_APPLICABLE ⇒ NULL (no valence)")
                .isNull();

        // The coherence invariant the migration's CHECK (changeSet -61) enforces holds for every
        // seeded row: assessment IS NULL  <=>  presence = 'NOT_APPLICABLE'.
        Integer violations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM observation " + "WHERE (presence = 'NOT_APPLICABLE') <> (assessment IS NULL) "
                        + "AND practice_id IN (?, ?)",
                Integer.class,
                desirablePracticeId,
                undesirablePracticeId);
        assertThat(violations)
                .as("assessment IS NULL iff presence = NOT_APPLICABLE for every backfilled row")
                .isZero();
    }

    /** Seeds one observation row for a practice + presence, assessment left NULL. Returns its id. */
    private UUID seedObservation(long practiceId, String presence) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO observation (id, practice_id, presence) VALUES (?, ?, ?)", id, practiceId, presence);
        return id;
    }

    private @Nullable String assessmentOf(UUID observationId) {
        return jdbcTemplate.queryForObject(
                "SELECT assessment FROM observation WHERE id = ?", String.class, observationId);
    }
}
