package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1ConstraintDatabaseTest {
    private static final String REVIEW = "00000000-0000-0000-0000-000000978011";
    private static final String RESULT = "00000000-0000-0000-0000-000000978021";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldEnforceCompleteChildrenAndRestoreTheSeverityCacheOnRollback(String context) throws Exception {
        var database = NativePracticeFixture.resultParent(context);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, NativePracticeFixture.insertResult("NOT_MET", "NULL")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("invalid problem count");
        assertThatThrownBy(() ->
                        PracticeMigrationFixture.execute(database, NativePracticeFixture.insertResult("MET", "NULL")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("ck_result_coverage");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, NativePracticeFixture.insertResult("COULD_NOT_DETERMINE", "NULL")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("ck_result_open_questions");
        try (var connection =
                DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(NativePracticeFixture.insertResult("NOT_MET", "NULL"));
                statement.execute("INSERT INTO"
                        + " problem(id,workspace_id,result_id,position,severity,summary,explanation,citations,basis)"
                        + " VALUES('00000000-0000-0000-0000-000000978031',978001,'"
                        + RESULT
                        + "',0,'MINOR','Missing purpose','Describe the"
                        + " purpose.','[{\"sourcePath\":\"description.md\",\"quote\":\"Retry\"}]','{}')");
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                connection.commit();
                statement.execute("INSERT INTO"
                        + " problem(id,workspace_id,result_id,position,severity,summary,explanation,citations,basis)"
                        + " VALUES(gen_random_uuid(),978001,'"
                        + RESULT
                        + "',1,'CRITICAL','Other purpose','Describe the"
                        + " purpose.','[{\"sourcePath\":\"description.md\",\"quote\":\"Retry\"}]','{}')");
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
                try (var rows = statement.executeQuery("SELECT severity FROM result WHERE id='" + RESULT + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("CRITICAL");
                }
                connection.rollback();
            }
        }
        assertThat(PracticeMigrationFixture.column(database, "SELECT severity FROM result WHERE id='" + RESULT + "'"))
                .containsExactly("MINOR");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "DELETE FROM problem WHERE result_id='" + RESULT + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("invalid problem count");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "UPDATE result SET summary='Rewrite the claim' WHERE id='" + RESULT + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("immutable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRejectIncompleteProofAndCrossTenantParents(String context) throws Exception {
        var database = NativePracticeFixture.resultParent(context);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "BEGIN; " + NativePracticeFixture.insertResult("NOT_MET", "NULL")
                                + "; INSERT INTO"
                                + " problem(id,workspace_id,result_id,position,severity,summary,explanation,citations)"
                                + " VALUES(gen_random_uuid(),978001,'"
                                + RESULT
                                + "',0,'MINOR','Missing purpose','Describe the purpose.','[]'); SET"
                                + " CONSTRAINTS ALL IMMEDIATE; COMMIT"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("incomplete problem proof");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        "INSERT INTO item(workspace_id,kind,parent_id,path) SELECT 978002,'scm.file',item_id,'file.ts'"
                                + " FROM review WHERE id='"
                                + REVIEW + "'"))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("parent must already exist");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        NativePracticeFixture.insertResult("MET", "'{}'")
                                .replace("item_version,978003", "'wrong-version',978003")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("frozen review assignment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepNobodyInTheCurrentClaimUniquenessKey(String context) throws Exception {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(
                database,
                NativePracticeFixture.insertResult("MET", "'{}'").replace("item_version,978003", "item_version,NULL"));
        PracticeMigrationFixture.execute(database, """
            INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
            SELECT '00000000-0000-0000-0000-000000978012',workspace_id,item_id,kind,item_version,origin,status,ordered_at
            FROM review WHERE id='00000000-0000-0000-0000-000000978011';
            INSERT INTO review_practice(review_id,workspace_id,practice_id,practice_version_id,status)
            VALUES('00000000-0000-0000-0000-000000978012',978001,978004,978005,'READY');
            INSERT INTO review_item(review_id,workspace_id,practice_id,item_id,item_version)
            SELECT id,workspace_id,978004,item_id,item_version FROM review
            WHERE id='00000000-0000-0000-0000-000000978012';
            """);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        NativePracticeFixture.insertResult("MET", "'{}'")
                                .replace("item_version,978003", "item_version,NULL")
                                .replace(REVIEW, "00000000-0000-0000-0000-000000978012")
                                .replace(RESULT, "00000000-0000-0000-0000-000000978022")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("uk_result_current");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRejectFreshReviewGrainDuplicatesEvenAfterTheFirstClaimEnds(String context) throws Exception {
        var database = NativePracticeFixture.resultParent(context);
        PracticeMigrationFixture.execute(database, NativePracticeFixture.insertResult("MET", "'{}'"));
        PracticeMigrationFixture.execute(
                database, "UPDATE result SET ended_at=recorded_at,end_reason='REPLACED' WHERE id='" + RESULT + "'");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database,
                        NativePracticeFixture.insertResult("MET", "'{}'")
                                .replace(RESULT, "00000000-0000-0000-0000-000000978022")))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("uk_result_review_claim");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepMigratedHistoryButRefuseANewResultWithItsEvidenceVersion(String context) throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase(context);
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT (count(*) > 0)::text FROM result WHERE evidence_version=0"))
                .containsExactly("true");
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
            INSERT INTO result SELECT gen_random_uuid(),workspace_id,review_id,practice_id,practice_version_id,item_id,
                item_version,developer_id,outcome,severity,summary,explanation,evidence,example,coverage,
                evidence_version,recorded_at,ended_at,end_reason FROM result WHERE evidence_version=0 LIMIT 1
            """))
                .hasRootCauseInstanceOf(SQLException.class)
                .hasMessageContaining("new results require evidence version 1");
    }
}
