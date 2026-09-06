package de.tum.cit.aet.hephaestus.core.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Lets an installation that still holds mentor chat history upgrade without a hand-run migration.
 *
 * <p>Changelog {@code 1778756946278} adds {@code chat_message.parts}, backfills it by reading
 * {@code chat_message_part}, and then drops that table behind a guard that raises unless it is
 * empty. Nothing ever empties it: no changeset deletes those rows, and the follow-up changelog its
 * own comment names ({@code 1778800000000}) was never written. The guard therefore fails for every
 * database that used the mentor before that release — the application exits at boot and stays down —
 * while a fresh install and an already-upgraded instance pass, because the table is empty or gone.
 * A released changelog is immutable, so the chain cannot repair itself: the drop is reached before
 * any changelog appended after it.
 *
 * <p>This performs, before Liquibase opens the chain, exactly what changesets 1 and 2 of that
 * changelog do — add the column, reconstruct each message's parts — and then removes the rows the
 * backfill has just copied. Those two changesets carry preconditions that mark them as run when
 * their work is already present, so the chain proceeds and the drop finds the empty table it
 * expects. Every statement is idempotent and the whole thing is a no-op once
 * {@code chat_message_part} is gone, which is every installation from the next release onward.
 */
@Component
class ChatMessagePartUpgradeRepair implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatMessagePartUpgradeRepair.class);

    /** Mirrors changeset {@code mentor-1071-add-parts-column}: JSONB, defaulted so reads stay non-null. */
    private static final String ADD_PARTS_COLUMN =
            "ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS parts JSONB DEFAULT '[]'::jsonb";

    /** Verbatim from changeset {@code mentor-1071-backfill-parts}, so both write the same shape. */
    private static final String BACKFILL_PARTS = """
            UPDATE chat_message m
               SET parts = COALESCE(sub.parts_array, '[]'::jsonb)
              FROM (
                SELECT
                    p.message_id,
                    jsonb_agg(
                        COALESCE(p.content, '{}'::jsonb)
                            || jsonb_build_object(
                                'type',
                                COALESCE(p.original_type, REPLACE(LOWER(p.type), '_', '-'))
                            )
                        ORDER BY p.order_index
                    ) AS parts_array
                  FROM chat_message_part p
                 GROUP BY p.message_id
              ) sub
             WHERE m.id = sub.message_id
               AND (m.parts IS NULL OR m.parts = '[]'::jsonb)
            """;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SpringLiquibase liquibase) {
            DataSource dataSource = liquibase.getDataSource();
            if (dataSource != null) {
                repair(dataSource);
            }
        }
        return bean;
    }

    private void repair(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            if (!legacyPartsTableExists(statement)) {
                return;
            }
            statement.execute(ADD_PARTS_COLUMN);
            int reconstructed = statement.executeUpdate(BACKFILL_PARTS);
            int removed = statement.executeUpdate("DELETE FROM chat_message_part");
            log.warn(
                    "Repaired the mentor chat-parts upgrade before Liquibase: reconstructed {} message(s) "
                            + "and cleared {} legacy part row(s) the drop guard would have refused to pass.",
                    reconstructed,
                    removed);
        } catch (SQLException e) {
            // Loud, but never fatal: an installation this does not apply to must still boot, and the
            // changelog's own guard remains the backstop for one this failed to prepare.
            log.error("Could not prepare the mentor chat-parts upgrade; Liquibase will report what it finds", e);
        }
    }

    private static boolean legacyPartsTableExists(Statement statement) throws SQLException {
        try (var rows = statement.executeQuery("SELECT to_regclass('public.chat_message_part') IS NOT NULL")) {
            return rows.next() && rows.getBoolean(1);
        }
    }
}
