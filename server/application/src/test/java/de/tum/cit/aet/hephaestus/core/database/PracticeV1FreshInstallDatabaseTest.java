package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1FreshInstallDatabaseTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldInstallTheFinalSchemaWhenTheDatabaseIsEmpty(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "practice_v1_fresh_" + UUID.randomUUID().toString().replace("-", ""));
        PostgreSQLTestContainer.migrateDatabase(database, context);
        try (var input =
                Objects.requireNonNull(getClass().getResourceAsStream("/db/practice-native/assert-migrated.sql"))) {
            PracticeMigrationFixture.execute(database, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT count(*)::text FROM information_schema.tables WHERE table_schema='public' AND"
                                        + " table_name IN"
                                        + " ('item','item_event','review','review_practice','review_item','review_job','result','problem','result_mark','membership','identity','identity_restriction','feedback','feedback_basis','feedback_copy','feedback_send','feedback_decision','feedback_response','practice_version','catalog_entry')"))
                .containsExactly("20");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='sfk_result_version'"))
                .containsExactly(
                        "FOREIGN KEY (practice_version_id, practice_id, workspace_id) REFERENCES practice_version(id,"
                                + " practice_id, workspace_id) ON DELETE RESTRICT");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT column_name || '|' || is_generated FROM information_schema.columns WHERE"
                                + " table_schema='public' AND table_name='practice_version' AND column_name IN"
                                + " ('name','review_digest') ORDER BY column_name"))
                .containsExactly("name|ALWAYS", "review_digest|ALWAYS");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (count(*)=2)::text FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE"
                                + " c.relname IN ('uk_result_current','uk_result_review_claim') AND"
                                + " i.indnullsnotdistinct"))
                .containsExactly("true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM information_schema.columns WHERE table_schema='public' AND"
                                + " table_name IN ('repository','issue','pull_request_review') AND"
                                + " column_name='workspace_id'"))
                .containsExactly("0");
    }
}
