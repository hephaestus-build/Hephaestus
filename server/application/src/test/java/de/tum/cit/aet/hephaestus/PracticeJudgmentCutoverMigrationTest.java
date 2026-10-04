package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@Tag("database")
class PracticeJudgmentCutoverMigrationTest {
    private static final String CUTOVER = "1791017720918_changelog.xml";
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("practice_judgment_cutover");
    private static final String REVIEWED =
            "{\"automatedReview\":{\"mode\":\"LANGUAGE_MODEL\",\"evidenceSufficiency\":\"SUFFICIENT_WHEN_REQUIREMENTS_MET\"}}";
    private static final String WITHDRAWN =
            "{\"automatedReview\":{\"mode\":\"LANGUAGE_MODEL\",\"evidenceSufficiency\":\"DECLARED_EVIDENCE_INSUFFICIENT\"}}";
    private static final String MANUAL =
            "{\"automatedReview\":{\"mode\":\"NONE\",\"evidenceSufficiency\":\"SUFFICIENT_WHEN_REQUIREMENTS_MET\"}}";

    @Test
    void shouldGiveReviewedPracticesTheHolisticJudgmentAndEnforceIt() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            var pending = liquibase.listUnrunChangeSets(new Contexts("prod"), new LabelExpression());
            int index = java.util.stream.IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CUTOVER))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(index, new Contexts("prod"), new LabelExpression());
        }
        seed();
        migrate();

        String holistic = "has_occasion,meets_standard,major_shortfall,critical_shortfall|"
                + "no-occasion,critical-shortfall,major-shortfall,shortfall,met";
        assertThat(scalar(judgmentShape("judgment") + " FROM practice WHERE id=992201"))
                .isEqualTo(holistic);
        // What the editor offers as the starting questions, so a migrated practice nobody edited reads as unchanged.
        assertThat(JsonMapper.builder()
                        .build()
                        .readValue(
                                scalar("SELECT judgment::text FROM practice WHERE id=992201"), PracticeJudgment.class))
                .isEqualTo(PracticeJudgment.holistic());
        assertThat(scalar(judgmentShape("judgment") + " FROM practice_revision WHERE id=992302"))
                .isEqualTo(holistic);
        assertThat(scalar(judgmentShape("adopted_base->'judgment'") + " FROM practice WHERE id=992201"))
                .isEqualTo(holistic);
        assertThat(scalar(judgmentShape("judgment") + " FROM curated_practice_override WHERE slug='judged'"))
                .isEqualTo(holistic);
        assertThat(scalar(judgmentShape("adopted_base->'judgment'")
                        + " FROM curated_practice_override WHERE slug='judged'"))
                .isEqualTo(holistic);
        // Earlier revisions never asked these questions; a practice nobody reviews automatically asks none.
        assertThat(scalar("SELECT (judgment IS NULL)::text FROM practice_revision WHERE id=992301"))
                .isEqualTo("true");
        assertThat(scalar("SELECT (judgment IS NULL)::text FROM practice WHERE id=992202"))
                .isEqualTo("true");
        assertThat(scalar("SELECT (judgment IS NULL)::text FROM curated_practice_override WHERE slug='manual'"))
                .isEqualTo("true");
        assertThat(scalar(judgmentShape("judgment") + " FROM practice WHERE id=992203"))
                .isEqualTo(holistic);

        assertThat(
                        scalar(
                                "SELECT string_agg(coalesce(severity,'-') || ':' || (answers IS NULL)::text || ':' || (rule_id IS NULL)::text, ',' ORDER BY summary) FROM observation WHERE workspace_id=992101"))
                .isEqualTo("MINOR:true:true,MAJOR:true:true,-:true:true");

        execute(
                "UPDATE curated_practice_override SET based_on_digest = 'practice:v4:' || repeat('d', 64) WHERE slug='judged'");
        assertThatThrownBy(
                        () -> execute(
                                "UPDATE curated_practice_override SET based_on_digest = 'practice:v5:' || repeat('d', 64) WHERE slug='judged'"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE practice SET judgment=NULL WHERE id=992201"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                        "UPDATE practice SET judgment=(SELECT judgment FROM practice WHERE id=992201) WHERE id=992202"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE practice_revision SET judgment=NULL WHERE id=992302"))
                .isInstanceOfSatisfying(
                        SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo("55000"));
        assertThatThrownBy(() -> execute("UPDATE observation SET severity='INFO' WHERE workspace_id=992101"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() ->
                        execute("UPDATE observation SET rule_id='met' WHERE workspace_id=992101 AND answers IS NULL"))
                .isInstanceOf(SQLException.class);
        execute("UPDATE observation SET answers='[]', rule_id='met' WHERE workspace_id=992101 AND summary='1'");
    }

    private static String judgmentShape(String column) {
        return "SELECT (SELECT string_agg(q->>'key', ',') FROM jsonb_array_elements(" + column
                + "->'questions') q) || '|' || (SELECT string_agg(r->>'id', ',') FROM jsonb_array_elements(" + column
                + "->'rules') r)";
    }

    private static void seed() throws SQLException {
        String practice =
                "INSERT INTO practice(id,workspace_id,slug,name,applies_to,signals,evidence_requirements,review_when,"
                        + "subject,criteria,created_at,display_order,automated_review_policy,delivery_behavior) "
                        + "VALUES(%d,992101,'%s','Judged','scm.pull_request','[\"scm.pull_request.created\"]','[]','{}','AUTHOR',"
                        + "'The change explains its purpose.',now(),%d,'%s','{\"summaryOnly\":false}')";
        String revision = "INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,signals,"
                + "evidence_requirements,review_when,subject,criteria,created_at,automated_review_policy,delivery_behavior,"
                + "review_rule_fingerprint) SELECT %d,id,%d,slug,name,applies_to,signals,evidence_requirements,review_when,"
                + "subject,criteria,now(),automated_review_policy,delivery_behavior,'v5:' || repeat('%s',64) "
                + "FROM practice WHERE id=%d";
        execute(
                """
            INSERT INTO workspace(id,account_login,account_type,display_name,slug,status,is_publicly_viewable)
            VALUES(992101,'judgment','ORG','Judgment','judgment','ACTIVE',false)
            """,
                """
            INSERT INTO identity_provider(id,type,server_url) VALUES(992102,'GITHUB','https://judgment.example')
            """,
                """
            INSERT INTO "user"(id,native_id,provider_id,login) VALUES(992103,992103,992102,'developer')
            """,
                """
            INSERT INTO agent_job(id,workspace_id,job_type,status,config_snapshot,job_token,retry_count,created_at,available_at,trace_id)
            VALUES('00000000-0000-0000-0000-000000992104',992101,'PRACTICE_REVIEW','COMPLETED','{}','test',0,now(),now(),repeat('b',32))
            """,
                String.format(practice, 992201, "judged", 1, REVIEWED),
                String.format(practice, 992202, "manual", 2, MANUAL),
                String.format(practice, 992203, "withdrawn", 3, WITHDRAWN),
                String.format(revision, 992301, 1, "a", 992201),
                String.format(revision, 992302, 2, "b", 992201),
                String.format(revision, 992303, 1, "c", 992202),
                String.format(revision, 992304, 1, "e", 992203),
                "UPDATE practice SET current_revision_id=992302, adopted_base=jsonb_build_object('criteria','The change explains its purpose.','automatedReviewPolicy','"
                        + REVIEWED + "'::jsonb), adopted_base_source='BUNDLED' WHERE id=992201",
                "UPDATE practice SET current_revision_id=992303 WHERE id=992202",
                "UPDATE practice SET current_revision_id=992304 WHERE id=992203",
                "INSERT INTO curated_practice_override(slug,name,applies_to,signals,evidence_requirements,review_when,subject,criteria,created_at,updated_at,version,automated_review_policy,adopted_base,adopted_base_source) "
                        + "VALUES('judged','Judged','scm.pull_request','[\"scm.pull_request.created\"]','[]','{}','AUTHOR','The change explains its purpose.',now(),now(),0,'"
                        + REVIEWED
                        + "',jsonb_build_object('criteria','The change explains its purpose.','automatedReviewPolicy','"
                        + REVIEWED + "'::jsonb),'BUNDLED')",
                "INSERT INTO curated_practice_override(slug,name,applies_to,signals,evidence_requirements,review_when,subject,criteria,created_at,updated_at,version,automated_review_policy) "
                        + "VALUES('manual','Manual','scm.pull_request','[\"scm.pull_request.created\"]','[]','{}','AUTHOR','The change explains its purpose.',now(),now(),0,'"
                        + MANUAL + "')",
                """
            INSERT INTO observation(id,occurrence_key,agent_job_id,workspace_id,practice_id,practice_revision_id,artifact_kind,artifact_id,about_user_id,summary,outcome,severity,origin,observed_at)
            SELECT md5('judgment'||i::text)::uuid, 'judgment:'||i,'00000000-0000-0000-0000-000000992104',992101,992201,992302,'scm.pull_request',992401,992103,i::text,outcome,severity,'LIVE',now()
            FROM (VALUES (1,'NOT_MET','INFO'),(2,'NOT_MET','MAJOR'),(3,'MET',NULL)) AS input(i,outcome,severity)
            """);
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                for (String sql : statements) statement.execute(sql);
                connection.commit();
            }
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return java.util.Objects.requireNonNull(rows.getString(1));
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }

    private static Liquibase liquibase() throws Exception {
        return new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connect())));
    }

    private static void migrate() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            liquibase.update(new Contexts("prod"));
        }
    }
}
