package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class SlackResearchOptOutMigrationTest {
    private static final String CHANGELOG = "1793149918875_changelog.xml";
    private static final String CONTEXT = "prod";
    private static final String DECISIONS = """
            SELECT account_id||':'||granted||':'||mechanism||':'||notice_version||':'||to_char(occurred_at AT TIME ZONE 'UTC','MM-DD')
              FROM consent_decision
             WHERE account_id BETWEEN 996001 AND 996007 AND purpose='RESEARCH_PARTICIPATION'
             ORDER BY account_id, occurred_at, id
            """;

    @Test
    void shouldCarryEachLinkedSlackResearchOptOutIntoTheConsentLedgerBeforeDroppingTheColumns() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase("slack_research_opt_out_migration");
        PracticeMigrationFixture.upgradeBefore(database, CONTEXT, CHANGELOG);
        PracticeMigrationFixture.execute(database, """
                SET session_replication_role = replica;
                INSERT INTO workspace(id, slug, account_login, account_type, display_name, status)
                VALUES (996001, 'opt-out-one', 'one', 'ORG', 'One', 'ACTIVE'),
                       (996002, 'opt-out-two', 'two', 'ORG', 'Two', 'ACTIVE');
                INSERT INTO identity_provider(type, server_url) VALUES ('SLACK', 'https://slack.com')
                ON CONFLICT DO NOTHING;
                INSERT INTO connection(workspace_id, kind, config)
                VALUES (996001, 'SLACK', '{"teamId":"T996001"}'), (996002, 'SLACK', '{"teamId":"T996002"}');
                INSERT INTO account(id, display_name) OVERRIDING SYSTEM VALUE
                VALUES (996001, 'Linked'), (996002, 'Two workspaces'), (996004, 'Never opted out'),
                       (996005, 'Already declined'), (996006, 'Granted later'), (996007, 'Link disabled');
                INSERT INTO identity(account_id, provider_id, provider_user_key, team_key, linked_via, disabled_at)
                SELECT account_id, p.id, subject, team, 'SIGN_IN', disabled_at
                  FROM identity_provider p, (VALUES
                       (996001, 'U1', 'T996001', NULL::timestamptz),
                       (996002, 'U2', 'T996001', NULL), (996002, 'U2', 'T996002', NULL),
                       (996004, 'U4', 'T996001', NULL),
                       (996005, 'U5', 'T996001', NULL),
                       (996006, 'U6', 'T996001', NULL),
                       (996007, 'U7', 'T996001', now())) links(account_id, subject, team, disabled_at)
                 WHERE p.type = 'SLACK' AND p.server_url = 'https://slack.com';
                INSERT INTO slack_participant_consent(workspace_id, slack_user_id, ingestion_opted_out,
                    research_opted_out, source, decided_at)
                VALUES (996001, 'U1', false, true, 'SLACK_APP_HOME', '2026-09-01T10:00Z'),
                       (996001, 'U2', false, true, 'SLACK_APP_HOME', '2026-09-02T10:00Z'),
                       (996002, 'U2', true, true, 'SLACK_APP_HOME', '2026-09-05T10:00Z'),
                       (996001, 'U3', false, true, 'SLACK_APP_HOME', '2026-09-01T10:00Z'),
                       (996001, 'U4', false, false, 'SLACK_APP_HOME', '2026-09-01T10:00Z'),
                       (996001, 'U5', false, true, 'SLACK_APP_HOME', '2026-09-01T10:00Z'),
                       (996001, 'U6', false, true, 'SLACK_APP_HOME', '2026-09-01T10:00Z'),
                       (996001, 'U7', false, true, 'SLACK_APP_HOME', '2026-09-01T10:00Z');
                INSERT INTO consent_decision(account_id, purpose, granted, mechanism, notice_version, occurred_at)
                VALUES (996005, 'RESEARCH_PARTICIPATION', false, 'ACCOUNT_SETTINGS', '2026-10-04', '2026-08-01T00:00Z'),
                       (996006, 'RESEARCH_PARTICIPATION', true, 'ACCOUNT_SETTINGS', '2026-10-04', '2026-10-10T00:00Z');
                SET session_replication_role = origin;
                """);

        try (var liquibase = liquibase(database)) {
            var contexts = new Contexts(CONTEXT);
            long changes = liquibase.listUnrunChangeSets(contexts, new LabelExpression()).stream()
                    .filter(change -> change.getFilePath().endsWith(CHANGELOG))
                    .count();
            liquibase.update((int) changes, contexts, new LabelExpression());
            assertThat(liquibase.listUnrunChangeSets(contexts, new LabelExpression()))
                    .noneMatch(change -> change.getFilePath().endsWith(CHANGELOG));
        }

        // The copy ran before the drop: U1 and U2 gain their opt-out; U2's is the later of its two
        // workspaces. U6's older opt-out is history behind its later grant. The unlinked U3, U4 without
        // an opt-out, U5 who already declines and U7 whose link is disabled gain nothing.
        assertThat(PracticeMigrationFixture.column(database, DECISIONS))
                .containsExactly(
                        "996001:false:ACCOUNT_SETTINGS:slack-app-home:09-01",
                        "996002:false:ACCOUNT_SETTINGS:slack-app-home:09-05",
                        "996005:false:ACCOUNT_SETTINGS:2026-10-04:08-01",
                        "996006:false:ACCOUNT_SETTINGS:slack-app-home:09-01",
                        "996006:true:ACCOUNT_SETTINGS:2026-10-04:10-10");
        assertThat(PracticeMigrationFixture.column(database, """
                        SELECT table_name||'.'||column_name FROM information_schema.columns
                         WHERE table_schema='public' AND (table_name, column_name) IN (
                               ('workspace_slug_history','redirect_expires_at'),
                               ('issue','review_snapshot_id'), ('issue','review_snapshot_digest'),
                               ('slack_participant_consent','research_opted_out'),
                               ('slack_participant_consent','source'))
                        """)).isEmpty();
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT slack_user_id FROM slack_participant_consent WHERE workspace_id=996001 ORDER BY 1"))
                .containsExactly("U1", "U2", "U3", "U4", "U5", "U6", "U7");

        try (var liquibase = liquibase(database)) {
            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts(CONTEXT), new LabelExpression()))
                    .hasStackTraceContaining("Restore the database backup taken before this upgrade");
        }
    }

    private static Liquibase liquibase(TestDatabase database) throws Exception {
        var connection = DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
        var liquibase = new Liquibase(
                "db/master.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)));
        // This fixture resumes a deliberately partial history before the production entry boundary.
        liquibase
                .getDatabaseChangeLog()
                .getPreconditions()
                .getNestedPreconditions()
                .clear();
        return liquibase;
    }
}
