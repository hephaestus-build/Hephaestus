package de.tum.cit.aet.hephaestus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class AchievementRemovalMigrationTest {

    private static final String REMOVAL = "1788884380638_changelog.xml";

    @Test
    void shouldDeleteAchievementStorageWithoutDeletingActivityOrProfiles() throws Exception {
        var fixture = PostgreSQLTestContainer.createDatabase("achievement_removal");
        var contexts = new Contexts("prod");
        var labels = new LabelExpression();
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                var pending = liquibase.listUnrunChangeSets(contexts, labels);
                int beforeRemoval = IntStream.range(0, pending.size())
                        .filter(index -> pending.get(index).getFilePath().endsWith(REMOVAL))
                        .findFirst()
                        .orElseThrow();
                liquibase.update(beforeRemoval, contexts, labels);

                try (var statement = connection.createStatement()) {
                    statement.execute("""
                        INSERT INTO "user" (id, native_id, provider_id, login)
                        SELECT 991001, 991001, id, 'achievement-removal-contributor'
                        FROM identity_provider WHERE type = 'GITHUB' AND server_url = 'https://github.com';
                        INSERT INTO workspace (
                            id, account_login, account_type, created_at, display_name, is_publicly_viewable,
                            slug, status, practices_enabled, mentor_enabled, achievements_enabled,
                            leaderboard_enabled, progression_enabled, leagues_enabled,
                            practice_review_auto_trigger_enabled, practice_review_manual_trigger_enabled
                        ) VALUES (
                            991002, 'achievement-removal', 'ORG', now(), 'Removal fixture', false,
                            'achievement-removal', 'ACTIVE', true, true, true, true, true, true, true, true
                        );
                        INSERT INTO activity_event (
                            id, event_key, event_type, occurred_at, actor_id, workspace_id,
                            target_type, target_id, xp, ingested_at
                        ) VALUES (
                            '00000000-0000-0000-0000-000000991003', 'achievement-removal-event',
                            'pull_request.opened', now(), 991001, 991002, 'pull_request', 991004, 12.5, now()
                        );
                        INSERT INTO user_achievement (id, user_id, achievement_id, progress_data, version)
                        VALUES ('00000000-0000-0000-0000-000000991005', 991001, 'historical-progress',
                            '{"type":"LinearAchievementProgress","current":3,"target":10}', 0);
                        """);
                }

                connection.commit();

                // Keep the fixture on this boundary when later migrations are appended.
                liquibase.update(2, contexts, labels);

                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("""
                            SELECT u.login, w.slug, e.xp, w.progression_enabled, w.leagues_enabled,
                                to_regclass('public.user_achievement') AS achievement_table,
                                EXISTS (SELECT 1 FROM information_schema.columns
                                    WHERE table_schema = 'public' AND table_name = 'workspace'
                                    AND column_name = 'achievements_enabled') AS achievement_flag
                            FROM "user" u JOIN activity_event e ON e.actor_id = u.id
                            JOIN workspace w ON w.id = e.workspace_id
                            WHERE e.id = '00000000-0000-0000-0000-000000991003'
                            """)) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("login")).isEqualTo("achievement-removal-contributor");
                    assertThat(rows.getString("slug")).isEqualTo("achievement-removal");
                    assertThat(rows.getDouble("xp")).isEqualTo(12.5);
                    assertThat(rows.getBoolean("progression_enabled")).isTrue();
                    assertThat(rows.getBoolean("leagues_enabled")).isTrue();
                    assertThat(rows.getString("achievement_table")).isNull();
                    assertThat(rows.getBoolean("achievement_flag")).isFalse();
                    assertThat(rows.next()).isFalse();
                }

                assertThatThrownBy(() -> liquibase.rollback(1, contexts, labels))
                        .hasStackTraceContaining("restore a pre-upgrade database backup");
            }
        }
    }
}
