package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ResearchDisclosureDatabaseTest {
    private static final String DISCLOSURE = "00000000-0000-0000-0000-000000978081";
    private static final String SUBJECT = "00000000-0000-0000-0000-000000978082";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepDisclosureSubjectsInTheirParentsWorkspaceAndRetainTheParent(String context) {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, disclosure(978001));
        assertForeignKey(
                database,
                "research_disclosure",
                "sfk_research_disclosure_workspace",
                "FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE RESTRICT");
        assertForeignKey(
                database,
                "research_disclosure_subject",
                "fk_research_disclosure_subject_disclosure",
                "FOREIGN KEY (disclosure_id, workspace_id) REFERENCES research_disclosure(id, workspace_id)");

        assertViolation(database, subject(978002), "23503");
        PracticeMigrationFixture.execute(database, subject(978001));
        assertViolation(database, "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'", "23503");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT workspace_id::text FROM research_disclosure_subject WHERE id='" + SUBJECT + "'"))
                .containsExactly("978001");
        PracticeMigrationFixture.execute(
                database,
                "DELETE FROM research_disclosure_subject WHERE id='" + SUBJECT + "';"
                        + "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'");
        PracticeMigrationFixture.execute(database, disclosure(978002));
        assertViolation(database, "DELETE FROM workspace WHERE id=978002", "23001");
        PracticeMigrationFixture.execute(database, "DELETE FROM research_disclosure WHERE id='" + DISCLOSURE + "'");
        assertViolation(database, disclosure(987654321), "23503");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldEnforceTheMigratedDisclosureAndRemovalStateChecks(String context) {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, disclosure(978001));
        PracticeMigrationFixture.execute(database, subject(978001));
        assertThat(PracticeMigrationFixture.column(database, """
                SELECT conname || ':' || convalidated::text FROM pg_constraint
                WHERE conrelid IN ('research_disclosure'::regclass,'research_disclosure_subject'::regclass)
                  AND contype='c' AND conname LIKE 'ck_research_disclosure%'
                ORDER BY conname
                """))
                .containsExactly(
                        "ck_research_disclosure_closure:true",
                        "ck_research_disclosure_counts:true",
                        "ck_research_disclosure_hash:true",
                        "ck_research_disclosure_purpose:true",
                        "ck_research_disclosure_schema:true",
                        "ck_research_disclosure_subject_grant:true",
                        "ck_research_disclosure_subject_key:true",
                        "ck_research_disclosure_subject_link:true",
                        "ck_research_disclosure_subject_pruned:true",
                        "ck_research_disclosure_subject_reason:true",
                        "ck_research_disclosure_subject_removed:true",
                        "ck_research_disclosure_use:true",
                        "ck_research_disclosure_window:true");
        for (String assignment : List.of(
                "purpose='TERMS'",
                "schema_name='other'",
                "schema_version=0",
                "window_from=window_to",
                "window_to=cutoff_at + interval '2 days'",
                "content_sha256='not-a-digest'",
                "participant_count=-1",
                "result_count=-1",
                "problem_count=-1",
                "feedback_count=-1",
                "feedback_excluded_count=-1",
                "use_until=created_at",
                "closure='DESTROYED'",
                "closed_at=now()",
                "closure='OTHER',closed_at=now()")) {
            assertViolation(
                    database,
                    "UPDATE research_disclosure SET " + assignment + " WHERE id='" + DISCLOSURE + "'",
                    "23514");
        }
        for (String assignment : List.of(
                "participant_key='not-a-participant-key'",
                "removal_required_at=now()",
                "removal_reason='OTHER',removal_required_at=now()",
                "account_id=NULL",
                "grant_decision_id=NULL",
                "removed_at=now()",
                "removed_at=now(),removal_reason='ACCOUNT_ERASED',removal_required_at=now()")) {
            assertViolation(
                    database,
                    "UPDATE research_disclosure_subject SET " + assignment + " WHERE id='" + SUBJECT + "'",
                    "23514");
        }
        // Erasure retains the grant and removal key; a completed downstream removal prunes both links.
        PracticeMigrationFixture.execute(
                database,
                "UPDATE research_disclosure_subject SET account_id=NULL,"
                        + "removal_reason='ACCOUNT_ERASED',removal_required_at=now() WHERE id='" + SUBJECT + "'");
        PracticeMigrationFixture.execute(
                database,
                "UPDATE research_disclosure_subject SET grant_decision_id=NULL," + "removed_at=now() WHERE id='"
                        + SUBJECT + "'");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (account_id IS NULL AND grant_decision_id IS NULL"
                                + " AND removed_at IS NOT NULL)::text FROM research_disclosure_subject WHERE id='"
                                + SUBJECT + "'"))
                .containsExactly("true");
    }

    private static String disclosure(long workspaceId) {
        return """
                INSERT INTO research_disclosure(id,workspace_id,purpose,schema_name,schema_version,
                    window_from,window_to,cutoff_at,notice_version,research_organization,created_at,
                    content_sha256,participant_count,result_count,problem_count,feedback_count,
                    feedback_excluded_count,use_until)
                VALUES ('%s',%d,'RESEARCH_PARTICIPATION','hephaestus.research-dataset',1,
                    '2026-10-01','2026-10-02','2026-10-02','2026-10-01','Research organisation','2026-10-02',
                    repeat('a',64),1,1,1,0,0,'2026-11-01')
                """.formatted(DISCLOSURE, workspaceId);
    }

    private static String subject(long workspaceId) {
        return "INSERT INTO research_disclosure_subject(id,disclosure_id,workspace_id,account_id,participant_key,grant_decision_id)"
                + " VALUES ('" + SUBJECT + "','" + DISCLOSURE + "'," + workspaceId
                + ",978003,'p_aaaaaaaaaaaaaaaaaaaaaaaaaa',978007)";
    }

    private static void assertForeignKey(TestDatabase database, String table, String name, String definition) {
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT convalidated::text || ':' || pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public."
                                + table + "'::regclass AND contype='f' AND conname='" + name + "'"))
                .containsExactly("true:" + definition);
    }

    private static void assertViolation(TestDatabase database, String sql, String sqlState) {
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, sql))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        failure -> assertThat(failure.getSQLState()).isEqualTo(sqlState));
    }
}
