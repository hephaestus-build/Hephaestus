package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class DigestContactCutoffMigrationTest {
    private static final String CHANGELOG = "-backfill-digest-contact-cutoffs.xml";

    @Test
    void shouldCloseALegacyCETDigestAtTheSummerOffset() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("digest_contact_cutoff");
        var contexts = new Contexts("prod");
        var labels = new LabelExpression();
        String stimulus = UUID.randomUUID().toString();
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            int beforeCutoffBackfill = (int) liquibase.listUnrunChangeSets(contexts, labels).stream()
                    .takeWhile(change -> !change.getFilePath().endsWith(CHANGELOG))
                    .count();
            liquibase.update(beforeCutoffBackfill, contexts, labels);
            PracticeMigrationFixture.execute(database, """
                INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
                VALUES (971801, 'w971801', 'Digest workspace', 'w971801', 'ORG', 'ACTIVE');
                INSERT INTO account (id, display_name) VALUES (971802, 'Digest developer');
                INSERT INTO conversation (id, workspace_id, account_id, created_at, last_activity_at)
                VALUES ('00000000-0000-0000-0000-000000971803', 971801, 971802, now(), now());
                INSERT INTO agent_run (id, workspace_id, kind, band, concurrency_key)
                VALUES ('00000000-0000-0000-0000-000000971804', 971801, 'DIGEST', 'BACKGROUND',
                    'conversation:00000000-0000-0000-0000-000000971803');
                INSERT INTO conversation_turn (id, workspace_id, conversation_id, account_id, request_id,
                    status, surface, run_id, created_at)
                VALUES ('00000000-0000-0000-0000-000000971805', 971801, '00000000-0000-0000-0000-000000971803',
                    971802, 'legacy-digest', 'QUEUED', 'SYSTEM', '00000000-0000-0000-0000-000000971804', now());
                INSERT INTO conversation_entry (id, workspace_id, conversation_id, account_id, seq, kind, surface,
                    text, payload, turn_id, created_at)
                VALUES ('%s', 971801, '00000000-0000-0000-0000-000000971803', 971802, 1, 'DIGEST', 'SYSTEM',
                    'Weekly recap', '{"window":{"weekStart":"2026-06-29","zone":"CET"},"feedbackIds":[],"changeKeys":[]}',
                    '00000000-0000-0000-0000-000000971805', now());
                """.formatted(stimulus));
            liquibase.update(contexts, labels);
        }
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT COALESCE(payload ->> 'startBy', 'missing') FROM conversation_entry
             WHERE workspace_id = 971801 AND id = '%s'
            """.formatted(stimulus)))
                .as("July 6 at 21:00 CET in Java is 19:00 UTC")
                .containsExactly("2026-07-06T19:00:00Z");
    }
}
