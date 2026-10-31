package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class ConversationAttemptLeaseDatabaseTest {
    private static final String CHANGELOG = "20261031185342-runtime-rt2-conversation-loop.xml";
    private static final String CHANGE_SET = "1793472822102-1";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void shouldPreserveExistingTurnsAndRequireALiveBindingWhenTheWorkerFenceIsRemoved(String context) throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase("conversation_lease_" + context + "_"
                + UUID.randomUUID().toString().replace("-", ""));
        try (Liquibase liquibase = liquibase(database)) {
            var pending = liquibase.listUnrunChangeSets(new Contexts(context), new LabelExpression());
            int prefix = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getId().equals(CHANGE_SET)
                            && pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(prefix, new Contexts(context), new LabelExpression());
        }
        PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
                VALUES(897451,'lease-migration','Lease migration','lease-migration','ORG','ACTIVE');
                INSERT INTO account(id,display_name,status) VALUES(897452,'Developer','ACTIVE');
                INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
                VALUES('00000000-0000-0000-0000-000000897451',897451,897452,now(),now());
                INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,
                                        attempt,worker_id,leased_until,created_at)
                VALUES('00000000-0000-0000-0000-000000897452',897451,
                       '00000000-0000-0000-0000-000000897451','lease-queued','QUEUED','APP',0,NULL,NULL,now()),
                      ('00000000-0000-0000-0000-000000897453',897451,
                       '00000000-0000-0000-0000-000000897451','lease-running','RUNNING','APP',1,
                       'old-worker',now()+interval '1 minute',now()),
                      ('00000000-0000-0000-0000-000000897454',897451,
                       '00000000-0000-0000-0000-000000897451','lease-done','DONE','APP',1,NULL,NULL,now());
                """);
        var before = PracticeMigrationFixture.column(
                database,
                "SELECT (to_jsonb(t)-'worker_id')::text FROM mentor_turn t WHERE workspace_id=897451 ORDER BY id");
        try (Liquibase liquibase = liquibase(database)) {
            liquibase.update(1, new Contexts(context), new LabelExpression());
        }
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT (to_jsonb(t)-'lease_id')::text FROM mentor_turn t WHERE workspace_id=897451 ORDER BY id"))
                .isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT count(*)::text FROM mentor_turn WHERE workspace_id=897451 AND lease_id IS NOT NULL"))
                .containsExactly("0");
        assertThatThrownBy(
                        () -> PracticeMigrationFixture.execute(
                                database,
                                "UPDATE mentor_turn SET lease_id=gen_random_uuid() WHERE workspace_id=897451 AND status='QUEUED'"))
                .hasRootCauseInstanceOf(SQLException.class);
        PracticeMigrationFixture.execute(
                database,
                "UPDATE mentor_turn SET lease_id=gen_random_uuid() WHERE workspace_id=897451 AND status='RUNNING'");
        try (Liquibase liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(context), new LabelExpression()))
                    .hasMessageContaining("Restore the verified pre-upgrade database backup");
        }
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        // The fixture starts before the migration under test, before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }
}
