package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A stored review-command publication from an earlier release loses the comment's words and its author's login, and
 * keeps every key that the listener reads. A publication of another event is unchanged.
 */
@Tag("database")
class ReviewCommandPublicationMigrationTest {
    private static final String CHANGELOG = "20261123220815-feat-mentoring-module-boundaries.xml";
    private static final String COMMAND = "de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent";

    @Test
    void shouldRemoveTheCommentWordsAndTheLoginFromAStoredReviewCommandAndKeepItsReferences() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "review_command_publication_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        UUID command = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, status)
                VALUES ('%s', 'review', '%s', '{"kind":"GITLAB","workspaceId":11,"itemId":22,"noteBody":"/hephaestus review please","noteAuthor":"someone","providerId":1,"authorNativeId":33,"commentId":44,"replyAddress":"7:abc"}', now(), 'FAILED'),
                       ('%s', 'other', 'de.tum.cit.aet.hephaestus.Other', '{"noteBody":"kept"}', now(), 'FAILED')
                """.formatted(command, COMMAND, other));

        try (var connection = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password())
                        .getConnection();
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"));
        }

        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT serialized_event::jsonb::text FROM event_publication WHERE id = '" + command + "'"))
                .containsExactly("{\"kind\": \"GITLAB\", \"itemId\": 22, \"commentId\": 44, \"providerId\": 1,"
                        + " \"workspaceId\": 11, \"replyAddress\": \"7:abc\", \"authorNativeId\": 33}");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT serialized_event FROM event_publication WHERE id = '" + other + "'"))
                .containsExactly("{\"noteBody\":\"kept\"}");
    }
}
