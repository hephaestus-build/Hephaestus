package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class DigestRunMigrationTest {
    private static final String CHANGELOG = "-run-digests-on-agent-runs.xml";
    private static final long WORKSPACE = 971901;
    private static final long ACCOUNT = 971902;

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldKeepExistingTurnsAndRequireOneRealSystemStimulusForDigestWork(String context) throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("digest_upgrade_" + context);
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        String conversation = UUID.randomUUID().toString();
        String originalRun = UUID.randomUUID().toString();
        String originalTurn = UUID.randomUUID().toString();
        String digestRun = UUID.randomUUID().toString();
        String digestTurn = UUID.randomUUID().toString();
        PracticeMigrationFixture.execute(
                database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(%1$d,'w%1$d','W%1$d','w%1$d','ORG','ACTIVE');
                INSERT INTO account(id,display_name) VALUES(%2$d,'Developer');
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('%3$s',%1$d,%2$d,now(),now());
                INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch)
                VALUES('%4$s',%1$d,'TURN','INTERACTIVE','conversation:%3$s','QUEUED',0,0);
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,surface,status,attempt,run_id,created_at,queued_at)
                VALUES('%5$s',%1$d,'%3$s','original-question','APP','QUEUED',0,'%4$s',now(),now());
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,turn_id,created_at)
                VALUES(gen_random_uuid(),%1$d,'%3$s',1,'USER','APP','Existing question','Existing question','%5$s',now());
                """.formatted(WORKSPACE, ACCOUNT, conversation, originalRun, originalTurn));
        PracticeMigrationFixture.execute(database, """
                INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,turn_id,created_at)
                VALUES(gen_random_uuid(),%1$d,'%2$s',2,'USER','APP','Duplicate stimulus','Duplicate stimulus','%3$s',now())
                """.formatted(WORKSPACE, conversation, originalTurn));
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(database, """
                CREATE UNIQUE INDEX CONCURRENTLY ux_mentor_turn_stimulus ON mentor_entry(workspace_id,turn_id)
                WHERE turn_id IS NOT NULL AND kind IN ('USER','DIGEST')
                """))
                .hasMessageContaining("duplicated");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT indisvalid::text FROM pg_index WHERE indexrelid='ux_mentor_turn_stimulus'::regclass"))
                .containsExactly("false");
        PracticeMigrationFixture.execute(
                database,
                "DELETE FROM mentor_entry WHERE workspace_id=" + WORKSPACE + " AND text='Duplicate stimulus'");
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT text FROM mentor_entry WHERE workspace_id=" + WORKSPACE + " AND turn_id='"
                                    + originalTurn + "'"))
                    .containsExactly("Existing question");
            PracticeMigrationFixture.execute(database, """
                    BEGIN;
                    INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch)
                    VALUES('%1$s',%2$d,'DIGEST','BACKGROUND','conversation:%3$s','QUEUED',0,0);
                    INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,surface,status,attempt,run_id,created_at,queued_at)
                    VALUES('%4$s',%2$d,'%3$s','digest-stimulus','SYSTEM','QUEUED',0,'%1$s',now(),now());
                    INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,turn_id,payload,created_at)
                    VALUES(gen_random_uuid(),%2$d,'%3$s',2,'DIGEST','SYSTEM','Weekly recap','Weekly recap','%4$s',
                      '{"window":{"weekStart":"2026-10-05","zone":"UTC"},"feedbackIds":[],"changeKeys":[]}',now());
                    COMMIT;
                    """.formatted(digestRun, WORKSPACE, conversation, digestTurn));
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database,
                            "UPDATE mentor_turn SET surface='APP' WHERE workspace_id=" + WORKSPACE + " AND id='"
                                    + digestTurn + "'"))
                    .hasMessageContaining("a conversation turn needs its conversation run kind");
            assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                            database, """
                    INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,search_text,turn_id,created_at)
                    VALUES(gen_random_uuid(),%1$d,'%2$s',3,'USER','APP','Invented question','Invented question','%3$s',now())
                    """.formatted(WORKSPACE, conversation, digestTurn)))
                    .hasMessageContaining("ux_mentor_turn_stimulus");
            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT indisvalid::text FROM pg_index WHERE indexrelid='ux_mentor_turn_stimulus'::regclass
                    """)).containsExactly("true");
            assertThatThrownBy(() -> PracticeMigrationFixture.rollbackOnly(liquibase, context, CHANGELOG))
                    .hasMessageContaining("Restore the verified database backup taken before proactive digest runs");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT surface FROM mentor_turn WHERE workspace_id=" + WORKSPACE + " AND id='" + digestTurn
                                    + "'"))
                    .containsExactly("SYSTEM");
            PracticeMigrationFixture.upgradeOnly(liquibase, context, CHANGELOG);
        }
    }
}
