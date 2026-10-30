package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PublicActivityHiddenUntilAnsweredMigrationTest {
    private static final String CHANGELOG = "20261031072932-public-activity-hidden-until-answered.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();

    /**
     * 995011 never answered (the old default). 995012 answered Show in the other workspace. 995013
     * answered Hide. 995014 chose Hide in User settings only. 995015 is an unclaimed roster member.
     * 995016 chose Show in User settings only, which counts as no answer. 995018 has no membership.
     */
    private static final String SEED = """
            INSERT INTO workspace(id,slug,display_name,account_login,account_type,status)
            VALUES (995001,'hidden-until-answered','Hidden until answered','hidden-until-answered','ORG','ACTIVE'),
                   (995002,'answered-elsewhere','Answered elsewhere','answered-elsewhere','ORG','ACTIVE');
            INSERT INTO account(id,display_name,status,public_activity_visible)
            VALUES (995011,'Never answered','ACTIVE',true),
                   (995012,'Answered show','ACTIVE',true),
                   (995013,'Answered hide','ACTIVE',false),
                   (995014,'Settings hide','ACTIVE',false),
                   (995015,'Roster member','UNCLAIMED',true),
                   (995016,'Settings show','ACTIVE',true),
                   (995018,'No membership','ACTIVE',true);
            INSERT INTO membership(workspace_id,account_id,role,public_activity_seen)
            VALUES (995001,995011,'MEMBER',false),
                   (995001,995012,'MEMBER',false),
                   (995002,995012,'MEMBER',true),
                   (995001,995013,'MEMBER',true),
                   (995001,995014,'MEMBER',false),
                   (995001,995015,'MEMBER',false),
                   (995001,995016,'MEMBER',false);
            """;

    private static final String VALUES =
            "SELECT id || ':' || public_activity_visible FROM account WHERE id BETWEEN 995011 AND 995018 ORDER BY id";

    @Test
    void shouldHideAccountsWithoutAWorkspaceAnswerAndRestoreExactlyThemOnRollback() throws Exception {
        var testDatabase = PostgreSQLTestContainer.createDatabase("public_activity_hidden_until_answered");
        try (var connection = DriverManager.getConnection(
                        testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            int changes = (int) pending.stream()
                    .filter(change -> change.getFilePath().endsWith(CHANGELOG))
                    .count();
            liquibase.update(before, CONTEXTS, LABELS);
            execute(connection, SEED);

            liquibase.update(changes, CONTEXTS, LABELS);

            assertThat(strings(connection, VALUES))
                    .containsExactly(
                            "995011:false",
                            "995012:true",
                            "995013:false",
                            "995014:false",
                            "995015:false",
                            "995016:false",
                            "995018:false");
            assertThat(strings(
                            connection,
                            "SELECT account_id::text FROM account_public_activity_hidden_by_default"
                                    + " WHERE account_id BETWEEN 995011 AND 995018 ORDER BY account_id"))
                    .containsExactly("995011", "995015", "995016", "995018");
            execute(connection, "INSERT INTO account(id,display_name,status) VALUES (995019,'New person','ACTIVE')");
            assertThat(strings(connection, "SELECT public_activity_visible::text FROM account WHERE id=995019"))
                    .containsExactly("false");
            // Erasure removes the record with the account.
            execute(connection, "DELETE FROM account WHERE id=995018");
            // A choice made after the upgrade removes the record, as saving a choice does, so a rollback keeps it.
            execute(connection, "DELETE FROM account_public_activity_hidden_by_default WHERE account_id=995016");

            liquibase.rollback(changes, CONTEXTS, LABELS);

            assertThat(strings(connection, VALUES))
                    .containsExactly(
                            "995011:true",
                            "995012:true",
                            "995013:false",
                            "995014:false",
                            "995015:true",
                            "995016:false");
            assertThat(strings(
                            connection,
                            "SELECT coalesce(to_regclass('public.account_public_activity_hidden_by_default')::text,"
                                    + " 'gone')"))
                    .containsExactly("gone");
            execute(connection, "INSERT INTO account(id,display_name,status) VALUES (995020,'Older person','ACTIVE')");
            assertThat(strings(connection, "SELECT public_activity_visible::text FROM account WHERE id=995020"))
                    .containsExactly("true");
            execute(connection, "DELETE FROM account WHERE id=995020");

            // Applied again, the change hides the same accounts, and the Hide made after the upgrade stays.
            liquibase.update(changes, CONTEXTS, LABELS);

            assertThat(strings(connection, VALUES))
                    .containsExactly(
                            "995011:false",
                            "995012:true",
                            "995013:false",
                            "995014:false",
                            "995015:false",
                            "995016:false");
            assertThat(strings(
                            connection,
                            "SELECT account_id::text FROM account_public_activity_hidden_by_default"
                                    + " WHERE account_id BETWEEN 995011 AND 995018 ORDER BY account_id"))
                    .containsExactly("995011", "995015");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        if (!connection.getAutoCommit()) connection.commit();
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            while (rows.next()) values.add(rows.getString(1));
        }
        if (!connection.getAutoCommit()) connection.commit();
        return values;
    }
}
