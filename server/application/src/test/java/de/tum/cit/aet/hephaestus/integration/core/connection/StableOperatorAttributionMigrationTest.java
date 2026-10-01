package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.stream.IntStream;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tests the upgrade against legacy attribution, not Hibernate's generated schema. */
@Tag("database")
class StableOperatorAttributionMigrationTest {
    private static final TestDatabase DATABASE = PostgreSQLTestContainer.createDatabase("person_attribution_migration");
    private static final String CHANGELOG = "1790837066126_changelog.xml";
    private static final Contexts CONTEXTS = new Contexts("prod");
    private static final LabelExpression LABELS = new LabelExpression();

    @BeforeAll
    static void migrateLegacyAttribution() throws Exception {
        try (Connection connection = connect();
                Liquibase liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            var pending = liquibase.listUnrunChangeSets(CONTEXTS, LABELS);
            int before = IntStream.range(0, pending.size())
                    .filter(i -> pending.get(i).getFilePath().endsWith(CHANGELOG))
                    .findFirst()
                    .orElseThrow();
            liquibase.update(before, CONTEXTS, LABELS);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                    INSERT INTO account (id,display_name) VALUES (995001,'42');
                    INSERT INTO workspace (id,account_login,account_type,display_name,is_publicly_viewable,slug,status)
                    VALUES (995001,'attribution-org','ORG','Attribution',false,'attribution-org','ACTIVE');
                    INSERT INTO connection (id,workspace_id,kind,instance_key,state,config)
                    VALUES (995001,995001,'SLACK','TATTRIBUTION','ACTIVE','{"type":"SLACK_APP","teamId":"TATTRIBUTION"}');
                    INSERT INTO connection_audit (id,connection_id,event_type,from_state,to_state,actor_kind,actor_ref,detail,occurred_at)
                    VALUES (995001,995001,'INITIATE','PENDING','ACTIVE','ADMIN','42','{"reason":"legacy admin"}','2026-09-01T12:00:00Z'),
                           (995002,995001,'OAUTH_COMPLETE','PENDING','ACTIVE','USER','login@example.org','{"name":"legacy user"}','2026-09-01T13:00:00Z'),
                           (995003,995001,'SUSPEND','ACTIVE','SUSPENDED','SYSTEM','provider-event','{"reason":"provider fact"}','2026-09-01T14:00:00Z');
                    INSERT INTO identity_provider(id,type,server_url) VALUES (995001,'GITLAB','https://migration-gitlab.example.org');
                    INSERT INTO "user"(id,provider_id,native_id,login) VALUES (995100,995001,987,'42');
                    INSERT INTO config_audit_event(id,occurred_at,workspace_id,actor_kind,actor_account_id,
                        entity_type,entity_id,action,changed_keys,old_value,new_value)
                    SELECT id,'2026-09-01T15:00:00Z'::timestamptz,995001,'USER',995001,
                        'WORKSPACE_ROLE',subject,'UPDATED',ARRAY['role'],
                        '{"role":"MEMBER","hidden":false}'::jsonb,'{"role":"ADMIN","hidden":false}'::jsonb
                    FROM (VALUES (995001,'42'),(995002,'login@example.org'),(995003,'995100')) AS legacy(id,subject);
                    UPDATE instance_settings SET silent_mode_changed_by='42',silent_mode_changed_at='2026-09-01T12:00:00Z';
                    UPDATE instance_llm_settings SET updated_by='login@example.org',updated_at='2026-09-01T13:00:00Z';
                    """);
            }
            connection.commit();
            liquibase.update(
                    (int) pending.stream()
                            .filter(c -> c.getFilePath().endsWith(CHANGELOG))
                            .count(),
                    CONTEXTS,
                    LABELS);
        }
    }

    @Test
    void shouldClearPersonalAttributionWithoutInferringNumericLookingReferences() throws SQLException {
        try (var connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT * FROM connection_audit WHERE id IN (995001,995002) ORDER BY id")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("event_type")).isEqualTo("INITIATE");
            assertThat(rows.getString("actor_ref")).isNull();
            assertThat(rows.getObject("actor_account_id")).isNull();
            assertThat(rows.getString("detail")).isNull();
            assertThat(rows.getString("from_state")).isEqualTo("PENDING");
            assertThat(rows.getString("to_state")).isEqualTo("ACTIVE");
            assertThat(rows.getTimestamp("occurred_at").toInstant().toString()).isEqualTo("2026-09-01T12:00:00Z");
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("event_type")).isEqualTo("OAUTH_COMPLETE");
            assertThat(rows.getString("actor_ref")).isNull();
            assertThat(rows.getString("detail")).isNull();
        }
    }

    @Test
    void shouldKeepProviderFactsAndSettingTimesWithoutBackfillingAccounts() throws SQLException {
        try (var connection = connect();
                var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT actor_ref,detail FROM connection_audit WHERE id=995003")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("provider-event");
                assertThat(rows.getString(2)).contains("provider fact");
            }
            try (var rows = statement.executeQuery(
                    "SELECT silent_mode_changed_by_account_id,silent_mode_changed_at FROM instance_settings WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
                assertThat(rows.getTimestamp(2).toInstant().toString()).isEqualTo("2026-09-01T12:00:00Z");
            }
            try (var rows = statement.executeQuery(
                    "SELECT updated_by_account_id,updated_at FROM instance_llm_settings WHERE id=1")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1)).isNull();
                assertThat(rows.getTimestamp(2).toInstant().toString()).isEqualTo("2026-09-01T13:00:00Z");
            }
            assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE connection_audit SET actor_account_id=99999999 WHERE id=995001"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("sfk_connection_audit_actor_account");
        }
    }

    @Test
    void shouldClearOnlyUnresolvedMembershipKeysAndKeepRoleChanges() throws SQLException {
        try (var connection = connect();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                        SELECT entity_id,old_value->>'role',new_value->>'role',occurred_at,actor_account_id
                        FROM config_audit_event WHERE id IN (995001,995002,995003) ORDER BY id
                        """)) {
            for (String subject : new String[] {"ERASED", "ERASED", "995100"}) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo(subject);
                assertThat(rows.getString(2)).isEqualTo("MEMBER");
                assertThat(rows.getString(3)).isEqualTo("ADMIN");
                assertThat(rows.getTimestamp(4).toInstant().toString()).isEqualTo("2026-09-01T15:00:00Z");
                assertThat(rows.getLong(5)).isEqualTo(995001L);
            }
            assertThat(rows.next()).isFalse();
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(DATABASE.jdbcUrl(), DATABASE.username(), DATABASE.password());
    }
}
