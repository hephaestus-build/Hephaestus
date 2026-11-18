package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Stored feedback that stopped as {@code APPROVAL_NO_LONGER_ELIGIBLE}, whose only producer was a lost view of the work,
 * reads {@code ITEM_NOT_VISIBLE} after the migration, and the old value is refused from then on.
 */
@Tag("database")
class ItemUnavailableStopReasonMigrationTest {
    private static final String FEEDBACK = "557f56cd-aaf1-4b81-ac27-eb1b7897553e";

    @Test
    void shouldRewriteTheOldReasonOnFeedbackAndItsCopiesAndRefuseItAfterwards() throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase("prod");

        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT stop_reason FROM feedback WHERE id='" + FEEDBACK + "'"))
                .containsExactly("ITEM_NOT_VISIBLE");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT DISTINCT stop_reason FROM feedback_copy WHERE feedback_id='" + FEEDBACK + "'"))
                .containsExactly("ITEM_NOT_VISIBLE");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM feedback WHERE stop_reason='APPROVAL_NO_LONGER_ELIGIBLE'"))
                .containsExactly("0");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "UPDATE feedback SET stop_reason='APPROVAL_NO_LONGER_ELIGIBLE' WHERE id='" + FEEDBACK + "'"))
                .hasMessageContaining("ck_feedback_stop_reason");
    }
}
