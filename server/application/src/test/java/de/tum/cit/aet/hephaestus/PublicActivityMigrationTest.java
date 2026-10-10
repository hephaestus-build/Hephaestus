package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
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
class PublicActivityMigrationTest {
    private static final String CHANGELOG = "1791603152317_changelog.xml";

    @Test
    void shouldDisablePublicationWithoutCarryingOverTheOldPublicFlag() throws Exception {
        var testDatabase = PostgreSQLTestContainer.createDatabase("public_activity_cutover");
        try (var connection = DriverManager.getConnection(
                        testDatabase.jdbcUrl(), testDatabase.username(), testDatabase.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var contexts = new Contexts("prod");
            var labels = new LabelExpression();
            var pending = liquibase.listUnrunChangeSets(contexts, labels);
            int before = IntStream.range(0, pending.size())
                    .filter(index -> pending.get(index).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, contexts, labels);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO workspace (id,account_login,account_type,display_name,is_publicly_viewable,slug,status)
                    VALUES (993001,'public-cutover','ORG','Public cutover',true,'public-cutover','ACTIVE');
                    INSERT INTO account(id,display_name,app_role,status,created_at,updated_at,version)
                    VALUES (993002,'Public person','USER','ACTIVE',now(),now(),0);
                    INSERT INTO workspace_member_onboarding(workspace_id,account_id,seen_revision,updated_at)
                    VALUES (993001,993002,5,now());
                    """);
            }
            connection.commit();
            int changes = (int) pending.stream()
                    .filter(change -> change.getFilePath().endsWith(CHANGELOG))
                    .count();
            liquibase.update(changes, contexts, labels);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                        SELECT w.public_activity_enabled,w.public_activity_search_engines,a.public_activity_visible,
                            o.public_activity_seen,o.seen_revision,
                            EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='workspace' AND column_name='is_publicly_viewable')
                        FROM workspace w JOIN workspace_member_onboarding o ON o.workspace_id=w.id
                        JOIN account a ON a.id=o.account_id WHERE w.id=993001
                        """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBoolean(1)).isFalse();
                assertThat(rows.getBoolean(2)).isFalse();
                assertThat(rows.getBoolean(3)).isTrue();
                assertThat(rows.getBoolean(4)).isFalse();
                assertThat(rows.getLong(5)).isEqualTo(5);
                assertThat(rows.getBoolean(6)).isFalse();
            }
        }
    }
}
