package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1RetentionDatabaseTest {

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRequireProductCleanupBeforeMembershipAndKeepTheSharedProviderRows(String context) throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase(context);
        assertThatThrownBy(() -> PracticeMigrationFixture.execute(
                        database, "DELETE FROM membership WHERE workspace_id=992101 AND account_id=992106"))
                .rootCause()
                .isInstanceOfSatisfying(
                        SQLException.class,
                        error -> assertThat(error.getSQLState()).isEqualTo("23001"));
        PracticeMigrationFixture.execute(database, """
            INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)
            VALUES('00000000-0000-0000-0000-000000996191',992101,992106,now(),now());
            INSERT INTO agent_run(id,workspace_id,kind,band,concurrency_key,state,attempt,lease_epoch,available_at,
                stop_reason,created_at,ended_at)
            VALUES('00000000-0000-0000-0000-000000996199',992101,'TURN','INTERACTIVE',
                'conversation:00000000-0000-0000-0000-000000996191','ENDED',0,0,now(),'COMPLETED',now(),now());
            INSERT INTO conversation_turn(id,workspace_id,conversation_id,request_id,status,surface,run_id,created_at)
            VALUES('00000000-0000-0000-0000-000000996192',992101,'00000000-0000-0000-0000-000000996191',
                'retention-turn','DONE','APP','00000000-0000-0000-0000-000000996199',now());
            INSERT INTO conversation_entry(id,workspace_id,conversation_id,seq,kind,surface,text,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000996193',992101,'00000000-0000-0000-0000-000000996191',1,
                'ANSWER','APP','Hephaestus answer.','00000000-0000-0000-0000-000000996192',now());
            INSERT INTO conversation(id,workspace_id,account_id,parent_conversation_id,parent_entry_id,
                created_at,last_activity_at)
            VALUES('00000000-0000-0000-0000-000000996194',992101,992106,'00000000-0000-0000-0000-000000996191',
                '00000000-0000-0000-0000-000000996193',now(),now());
            DELETE FROM feedback WHERE workspace_id=992101 AND developer_id=992106;
            DELETE FROM review_item WHERE workspace_id=992101 AND result_id IN
                (SELECT id FROM result WHERE workspace_id=992101 AND developer_id=992106);
            DELETE FROM result WHERE workspace_id=992101 AND developer_id=992106;
            DELETE FROM conversation WHERE workspace_id=992101 AND account_id=992106
                AND parent_conversation_id IS NULL;
            DELETE FROM membership WHERE workspace_id=992101 AND account_id=992106;
            """);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (SELECT count(*) FROM problem WHERE workspace_id=992101)::text || '|' ||"
                                + " (SELECT count(*) FROM result_mark WHERE workspace_id=992101) || '|' || (SELECT"
                                + " count(*) FROM feedback_copy WHERE workspace_id=992101) || '|' || (SELECT"
                                + " count(*) FROM conversation WHERE workspace_id=992101) || '|' || (SELECT"
                                + " count(*) FROM conversation_turn WHERE workspace_id=992101) || '|' || (SELECT"
                                + " count(*) FROM conversation_entry WHERE workspace_id=992101)"))
                .containsExactly("0|0|0|0|0|0");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (SELECT count(*) FROM issue WHERE id=992109)::text || '|' || (SELECT count(*)"
                                + " FROM repository WHERE id=992108) || '|' || (SELECT count(*) FROM membership"
                                + " WHERE workspace_id=992201)"))
                .containsExactly("1|1|1");
    }
}
