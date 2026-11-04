package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeCurrentResultDatabaseTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldUseTheReleasedJobOccasionBeforeObservationPopulation(String context) throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase(context);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT right(id::text,6) || '|' || origin || '|' || coalesce(right(event_id::text,6),'none')
            FROM review WHERE id BETWEEN '00000000-0000-0000-0000-000000992601'::uuid
                AND '00000000-0000-0000-0000-000000992606'::uuid ORDER BY id
            """))
                .containsExactly(
                        "992601|EVENT|992611",
                        "992602|REQUEST|none",
                        "992603|SCHEDULE|none",
                        "992604|BACKFILL|none",
                        "992605|EVENT|none",
                        "992606|REQUEST|none");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRefuseReuseOfReleasedEvidenceWithoutNativeAdmissionProof(String context) throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase(context);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "UPDATE review_item SET state='REUSED' WHERE review_id='00000000-0000-0000-0000-000000992111'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("new assignments cannot reuse released evidence");
    }
}
