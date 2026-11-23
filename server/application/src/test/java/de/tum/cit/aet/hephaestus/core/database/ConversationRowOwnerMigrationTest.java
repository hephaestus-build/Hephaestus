package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("database")
class ConversationRowOwnerMigrationTest {
    private static final String CHANGELOG = "-conversation-row-owners.xml";

    @Test
    void shouldBackfillTheOwnersOfTwoConversationsFromThePreviousRelease() throws Exception {
        var owner = previousDatabase();
        PracticeMigrationFixture.execute(owner, cutoverSql("seed"));
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,seq,kind,surface,text,created_at)
                SELECT gen_random_uuid(),979124,'97912400-0000-4000-8000-000000000001',seq,'ANSWER','APP','Answer',now()
                FROM generate_series(2,10002) AS extra(seq)
                """);
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
        PracticeMigrationFixture.execute(owner, cutoverSql("expect"));
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT count(*) FROM conversation_entry WHERE workspace_id=979124 AND account_id=979125
                """)).containsExactly("10002");

        for (var child : Map.of(
                        "conversation_entry", "97912400-0000-4000-8000-000000000021",
                        "conversation_turn", "97912400-0000-4000-8000-000000000011")
                .entrySet()) {
            assertThatThrownBy(() ->
                            PracticeMigrationFixture.execute(owner, """
                        UPDATE public.%s SET account_id=979126 WHERE id='%s'
                        """.formatted(child.getKey(), child.getValue())))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("Conversation not found");
        }

        PracticeMigrationFixture.execute(owner, """
                INSERT INTO agent_run (id,workspace_id,kind,band,concurrency_key,state,start_within_seconds)
                VALUES ('97912400-0000-4000-8000-000000000033',979124,'TURN','INTERACTIVE',
                    'conversation:97912400-0000-4000-8000-000000000001','QUEUED',180);
                INSERT INTO conversation_turn (id,workspace_id,conversation_id,run_id,request_id,surface,status,created_at)
                VALUES ('97912400-0000-4000-8000-000000000013',979124,'97912400-0000-4000-8000-000000000001',
                    '97912400-0000-4000-8000-000000000033','old-writer-turn','APP','QUEUED',now());
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,seq,kind,surface,text,created_at,account_id)
                VALUES ('97912400-0000-4000-8000-000000000023',979124,'97912400-0000-4000-8000-000000000001',
                    10003,'ANSWER','APP','After moving the owner',now(),979126);
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,seq,kind,surface,text,created_at)
                VALUES ('97912400-0000-4000-8000-000000000024',979124,'97912400-0000-4000-8000-000000000001',
                    10004,'ANSWER','APP','From the previous writer',now());
                """);
        assertThat(PracticeMigrationFixture.column(owner, """
                SELECT account_id FROM conversation_turn WHERE request_id='old-writer-turn'
                UNION ALL
                SELECT account_id FROM conversation_entry WHERE workspace_id=979124 AND seq IN (10003,10004)
                """)).containsExactly("979125", "979125", "979125");
    }

    @Test
    void shouldHaltIfAnOldRowHasNoParentToSupplyItsOwner() throws Exception {
        var owner = previousDatabase();
        PracticeMigrationFixture.execute(owner, cutoverSql("seed"));
        PracticeMigrationFixture.execute(owner, """
                ALTER TABLE conversation_entry DROP CONSTRAINT sfk_conversation_entry_conversation;
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,seq,kind,surface,text,created_at)
                VALUES ('97912400-0000-4000-8000-000000000029',979124,'97912400-0000-4000-8000-000000000099',
                    1,'ANSWER','APP','An orphan on an inconsistent database',now());
                """);
        assertThatThrownBy(() -> PostgreSQLTestContainer.migrateDatabase(owner, "prod"))
                .hasStackTraceContaining("The migration could not find an account for each row of conversation_entry.");
    }

    private static TestDatabase previousDatabase() throws Exception {
        String name = "conversation_owners_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        return owner;
    }

    private static String cutoverSql(String suffix) throws Exception {
        return new ClassPathResource("db/cutover-seeds/conversation-row-owners." + suffix + ".sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
