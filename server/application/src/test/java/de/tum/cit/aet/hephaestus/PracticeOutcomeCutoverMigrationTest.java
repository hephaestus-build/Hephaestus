package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@Tag("database")
class PracticeOutcomeCutoverMigrationTest {
    private static final String CUTOVER = "1790892486246_changelog.xml";
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("practice_outcome_cutover");
    private static final String BINDINGS = """
        [{"signals":["scm.pull_request.created"],"needs":[{"sourceKind":"scm.pull-request.core","stance":"REQUIRED"}],"subject":"AUTHOR","onDrafts":true}]
        """;

    @Test
    void shouldPreservePopulatedHistoryAndEnforceTheFlatContract() throws Exception {
        try (Liquibase liquibase = liquibase()) {
            var pending = liquibase.listUnrunChangeSets(new Contexts("prod"), new LabelExpression());
            int index = java.util.stream.IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CUTOVER))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(index, new Contexts("prod"), new LabelExpression());
        }
        seed();
        execute("UPDATE curated_practice_override SET bindings = bindings || bindings WHERE slug='cutover'");
        assertThatThrownBy(PracticeOutcomeCutoverMigrationTest::migrate)
                .hasStackTraceContaining("requires one complete occasion");
        assertThat(
                        scalar(
                                "SELECT count(*)::text FROM information_schema.columns WHERE table_name='observation' AND column_name='assessment_status'"))
                .isEqualTo("1");
        execute("UPDATE curated_practice_override SET bindings='" + BINDINGS + "' WHERE slug='cutover'");
        migrate();
        assertThat(
                        scalar(
                                "SELECT (review_when = '{\"draftStatus\":[\"NOT_DRAFT\"]}'::jsonb)::text FROM curated_practice_override WHERE slug='non-draft-cutover'"))
                .isEqualTo("true");
        assertThat(scalar("SELECT review_when::text FROM curated_practice_override WHERE slug='issue-cutover'"))
                .isEqualTo("{}");
        assertThat(
                        scalar(
                                "SELECT (adopted_base->'reviewWhen' = '{\"draftStatus\":[\"NOT_DRAFT\"]}'::jsonb)::text FROM curated_practice_override WHERE slug='non-draft-cutover'"))
                .isEqualTo("true");
        assertThatThrownBy(() ->
                        execute("UPDATE practice SET review_when='{\"draftStatus\":[\"NOT_DRAFT\"]}' WHERE id=991201"))
                .isInstanceOf(SQLException.class);
        assertThat(scalar(
                        "SELECT string_agg(outcome, ',' ORDER BY summary) FROM observation WHERE workspace_id=991101"))
                .isEqualTo("MET,NOT_MET,NOT_MET,MET,NOT_APPLICABLE,UNDETERMINED");
        assertThat(
                        scalar(
                                "SELECT count(*)::text FROM observation WHERE workspace_id=991101 AND (outcome='NOT_MET') <> (severity IS NOT NULL)"))
                .isEqualTo("0");
        assertThat(scalar("SELECT review_rule_fingerprint FROM practice_revision WHERE id=991301"))
                .isEqualTo("v4:" + "a".repeat(64));
        assertThat(
                        scalar(
                                "SELECT (signals IS NULL AND evidence_requirements IS NULL AND review_when IS NULL AND subject IS NULL AND review_rule_fingerprint IS NULL)::text FROM practice_revision WHERE id=991304"))
                .isEqualTo("true");
        execute("UPDATE practice_revision SET review_rule_fingerprint=NULL WHERE id=991302");
        for (String mutation : java.util.List.of("review_rule_fingerprint='v5:' || repeat('b',64)", "id=991303")) {
            assertThatThrownBy(() -> execute("UPDATE practice_revision SET " + mutation + " WHERE id=991302"))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("55000"));
        }
        assertThat(
                        scalar(
                                "SELECT (review_rule_fingerprint IS NULL)::text || ':' || criteria || ':' || subject || ':' || review_when::text FROM practice_revision WHERE id=991302"))
                .isEqualTo("true:The change explains its purpose.:AUTHOR:{}");
        assertThat(scalar("SELECT signals::text FROM practice WHERE id=991201"))
                .isEqualTo("[\"scm.pull_request.created\"]");
        assertThat(scalar("SELECT subject || ':' || review_when::text FROM practice WHERE id=991201"))
                .isEqualTo("AUTHOR:{}");
        assertThat(
                        scalar(
                                "SELECT (adopted_base ? 'bindings')::text || ':' || (adopted_base ? 'evidenceRequirements')::text FROM practice WHERE id=991201"))
                .isEqualTo("false:true");
        assertThat(
                        scalar(
                                "SELECT subject || ':' || review_when::text FROM curated_practice_override WHERE slug='cutover'"))
                .isEqualTo("AUTHOR:{}");
        assertThat(
                        scalar(
                                "SELECT (adopted_base ? 'bindings')::text || ':' || (adopted_base ? 'signals')::text FROM curated_practice_override WHERE slug='cutover'"))
                .isEqualTo("false:true");
        assertThatThrownBy(() -> execute("UPDATE practice SET subject='REVIEWER' WHERE id=991201"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE practice SET criteria='Diverged' WHERE id=991201"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE practice_revision SET criteria='Rewritten' WHERE id=991301"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() ->
                        execute("UPDATE observation SET outcome='MET' WHERE workspace_id=991101 AND outcome='NOT_MET'"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE observation SET outcome='UNKNOWN' WHERE workspace_id=991101"))
                .isInstanceOf(SQLException.class);
    }

    private static void seed() throws SQLException {
        execute(
                """
            INSERT INTO workspace(id,account_login,account_type,display_name,slug,status,is_publicly_viewable)
            VALUES(991101,'cutover','ORG','Cutover','cutover','ACTIVE',false)
            """,
                """
            INSERT INTO identity_provider(id,type,server_url) VALUES(991102,'GITHUB','https://cutover.example')
            """,
                """
            INSERT INTO "user"(id,native_id,provider_id,login) VALUES(991103,991103,991102,'developer')
            """,
                """
            INSERT INTO agent_job(id,workspace_id,job_type,status,config_snapshot,job_token,retry_count,created_at,available_at,trace_id)
            VALUES('00000000-0000-0000-0000-000000991104',991101,'PRACTICE_REVIEW','COMPLETED','{}','test',0,now(),now(),repeat('a',32))
            """,
                "INSERT INTO practice(id,workspace_id,slug,name,bindings,criteria,created_at,automated_review_policy,delivery_behavior) "
                        + "VALUES(991201,991101,'cutover','Cutover','" + BINDINGS
                        + "','The change explains its purpose.',now(),'{}','{\"summaryOnly\":false}')",
                "INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,bindings,criteria,created_at,automated_review_policy,delivery_behavior,review_rule_fingerprint) "
                        + "VALUES(991301,991201,1,'cutover','Cutover','scm.pull_request','" + BINDINGS
                        + "','The change explains its purpose.',now(),'{}','{\"summaryOnly\":false}','v4:' || repeat('a',64))",
                "INSERT INTO practice_revision(id,practice_id,revision_number,slug,name,applies_to,bindings,criteria,created_at,automated_review_policy,delivery_behavior) "
                        + "VALUES(991302,991201,2,'cutover','Cutover','scm.pull_request','" + BINDINGS
                        + "','The change explains its purpose.',now(),'{}','{\"summaryOnly\":false}')",
                """
            INSERT INTO practice_revision(id,practice_id,revision_number,criteria,created_at,automated_review_policy,delivery_behavior)
            VALUES(991304,991201,3,'Historical criteria without a recorded definition.',now(),'{}','{"summaryOnly":false}')
            """,
                "UPDATE practice SET current_revision_id=991301, adopted_base=jsonb_build_object('bindings','"
                        + BINDINGS
                        + "'::jsonb,'criteria','The change explains its purpose.'), adopted_base_source='BUNDLED' WHERE id=991201",
                "INSERT INTO curated_practice_override(slug,name,applies_to,bindings,criteria,created_at,updated_at,version,adopted_base,adopted_base_source) "
                        + "VALUES('cutover','Cutover','scm.pull_request','" + BINDINGS
                        + "','The change explains its purpose.',now(),now(),0,"
                        + "jsonb_build_object('bindings','" + BINDINGS
                        + "'::jsonb,'criteria','The change explains its purpose.'),'BUNDLED')",
                """
            INSERT INTO curated_practice_override(slug,name,applies_to,bindings,criteria,created_at,updated_at,version,adopted_base,adopted_base_source)
            SELECT 'non-draft-cutover',name,applies_to, replace(bindings::text, 'true', 'false')::jsonb, criteria,created_at,updated_at,version,
                   replace(adopted_base::text, 'true', 'false')::jsonb,adopted_base_source
            FROM curated_practice_override WHERE slug='cutover'
            """,
                """
            INSERT INTO curated_practice_override(slug,name,applies_to,bindings,criteria,created_at,updated_at,version)
            SELECT 'issue-cutover',name,'scm.issue',replace(bindings::text,'scm.pull_request.created','scm.issue.created')::jsonb,criteria,created_at,updated_at,version
            FROM curated_practice_override WHERE slug='non-draft-cutover'
            """,
                """
            INSERT INTO observation(id,occurrence_key,agent_job_id,workspace_id,practice_id,practice_revision_id,artifact_kind,artifact_id,about_user_id,summary,assessment_status,presence,assessment,severity,observed_at)
            SELECT md5(i::text)::uuid, 'cutover:'||i,'00000000-0000-0000-0000-000000991104',991101,991201,991301,'scm.pull_request',991401,991103,i::text,status,presence,assessment,severity,now()
            FROM (VALUES (1,'ASSESSED','PRESENT','GOOD',NULL),(2,'ASSESSED','PRESENT','BAD','MAJOR'),
                         (3,'ASSESSED','ABSENT','GOOD','MINOR'),(4,'ASSESSED','ABSENT','BAD',NULL),
                         (5,'NOT_APPLICABLE',NULL,NULL,NULL),(6,'UNDETERMINED',NULL,NULL,NULL))
            AS input(i,status,presence,assessment,severity)
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
