package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A ready export built before the export fence can hold a conversation message that an erasure removed after it was
 * built. The upgrade expires it, keeps an export in flight, and installs the fence on a populated database.
 */
@Tag("database")
class ExportErasedMessagesMigrationTest {
    private static final String CHANGELOG = "-fix-export-erased-conversation-text.xml";
    private static final long OWNER = 979301;

    @Test
    void shouldExpireEveryReadyExportAndKeepAnExportInFlight() throws Exception {
        String name = "export_erased_messages_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", CHANGELOG);
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO account(id, display_name) VALUES (%1$d, 'Reader');
                INSERT INTO account_export(id, account_id, status, completed_at, expires_at, payload)
                OVERRIDING SYSTEM VALUE
                VALUES (979302, %1$d, 'READY', now(), now() + interval '1 day', convert_to('Alex reviews.', 'UTF8')),
                    (979303, %1$d, 'PENDING', NULL, NULL, NULL);
                """.formatted(OWNER));

        try (var connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password());
                var liquibase = new Liquibase(
                        "db/master.xml",
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            PracticeMigrationFixture.upgradeOnly(liquibase, "prod", CHANGELOG);
        }

        assertThat(PracticeMigrationFixture.column(
                        owner,
                        "SELECT id || ':' || status || ':' || (payload IS NULL)::text FROM account_export"
                                + " WHERE account_id = " + OWNER + " ORDER BY id"))
                .containsExactly("979302:EXPIRED:true", "979303:PENDING:true");
        assertThat(PracticeMigrationFixture.column(
                        owner,
                        "SELECT tgname FROM pg_trigger WHERE tgrelid = 'public.conversation_entry'::regclass"
                                + " AND tgname LIKE 'trg_conversation_entry_%_exports' ORDER BY 1"))
                .containsExactly("trg_conversation_entry_changed_exports", "trg_conversation_entry_deleted_exports");
    }
}
