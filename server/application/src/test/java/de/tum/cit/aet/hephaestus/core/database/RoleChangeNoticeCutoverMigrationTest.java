package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.action.ActionState;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.Objects;
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
class RoleChangeNoticeCutoverMigrationTest {
    private static final String CHANGELOG = "20261114013648-harvest-role-change-notifications.xml";
    private static final String RECORD = "20261114143544-role-notice-correction-record.xml";

    @Test
    void shouldEndOldRoleNoticesWithoutResendingUncertainWritesOrChangingOtherSecurityMail() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "role_notice_cutover_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        UUID proposed = action(database, ActionState.PROPOSED, false, "APP_ROLE_CHANGED");
        UUID admitted = action(database, ActionState.ADMITTED, false, "APP_ROLE_CHANGED");
        UUID claimed = action(database, ActionState.EXECUTING, false, "APP_ROLE_CHANGED");
        UUID started = action(database, ActionState.EXECUTING, true, "APP_ROLE_CHANGED");
        UUID sent = action(database, ActionState.DONE, true, "APP_ROLE_CHANGED");
        UUID other = action(database, ActionState.ADMITTED, false, "PASSKEY_CHANGED");
        UUID publication = publication(database, "APP_ROLE_CHANGED");
        UUID otherPublication = publication(database, "PASSKEY_CHANGED");

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }

        for (UUID id : new UUID[] {proposed, admitted, claimed}) {
            assertThat(outcome(database, id)).isEqualTo("CANCELLED:WITHDRAWN");
        }
        assertThat(outcome(database, started)).isEqualTo("UNCERTAIN:LEASE_LOST_AFTER_WRITE");
        assertThat(outcome(database, sent)).isEqualTo("DONE:COMPLETED");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT readback_ref FROM agent_action WHERE id='" + sent + "'"))
                .containsExactly("sent-message-receipt");
        assertThat(PracticeMigrationFixture.column(database, """
                SELECT count(*)::text FROM agent_action
                WHERE id IN ('%s','%s','%s','%s') AND payload IS NULL AND leased_until IS NULL AND ended_at IS NOT NULL
                """.formatted(proposed, admitted, claimed, started)))
                .containsExactly("4");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT payload #>> '{event,kind}' FROM agent_action WHERE id='" + other
                                + "' AND state='ADMITTED'"))
                .containsExactly("PASSKEY_CHANGED");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT id::text FROM event_publication WHERE id='" + publication + "'"))
                .isEmpty();
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT serialized_event::jsonb ->> 'kind' FROM event_publication WHERE id='" + otherPublication
                                + "'"))
                .containsExactly("PASSKEY_CHANGED");
    }

    @Test
    void shouldConfirmTheCorrectionWhenNoOldNoticeIsOpen() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "role_notice_record_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", RECORD);
        UUID sent = action(database, ActionState.DONE, true, "APP_ROLE_CHANGED");
        UUID other = action(database, ActionState.ADMITTED, false, "PASSKEY_CHANGED");

        update(database, RECORD);

        assertThat(outcome(database, sent)).isEqualTo("DONE:COMPLETED");
        assertThat(PracticeMigrationFixture.column(database, "SELECT state FROM agent_action WHERE id='" + other + "'"))
                .containsExactly("ADMITTED");
    }

    @Test
    void shouldHaltWhenAnOldNoticeIsOpenAgainAfterTheCorrection() throws Exception {
        TestDatabase database = PostgreSQLTestContainer.createDatabase(
                "role_notice_reopened_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", RECORD);
        // A server of the previous release that ran beside the upgrade queues the old notice again.
        action(database, ActionState.PROPOSED, false, "APP_ROLE_CHANGED");

        assertThatThrownBy(() -> update(database, RECORD))
                .hasStackTraceContaining("Queued copies of the general role notice are open again");
    }

    private static void update(TestDatabase database, String changelog) throws Exception {
        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + changelog,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());
        }
    }

    private static UUID action(TestDatabase database, ActionState state, boolean writeStarted, String eventKind) {
        UUID id = UUID.randomUUID();
        boolean executing = state == ActionState.EXECUTING;
        boolean done = state == ActionState.DONE;
        String event = "jsonb_build_object('kind','ACCOUNT_SECURITY_CHANGED','event',jsonb_build_object("
                + "'accountId',1,'kind','" + eventKind + "','occurredAt','2026-11-10T12:00:00Z'))";
        PracticeMigrationFixture.execute(database, """
                INSERT INTO agent_action (id,kind,lane_key,idempotency_key,state,payload,attempts,max_attempts,
                    lease_epoch,leased_until,write_started_at,readback_ref,stop_reason,ended_at,
                    next_attempt_at,created_at,updated_at)
                VALUES ('%1$s','EMAIL','%1$s','%1$s','%2$s',%3$s,%4$d,3,%4$d,%5$s,%6$s,%7$s,%8$s,%9$s,
                    now(),now(),now())
                """.formatted(
                        id,
                        state,
                        done ? "NULL" : event,
                        executing || done ? 1 : 0,
                        executing ? "now() + interval '5 minutes'" : "NULL",
                        writeStarted ? "now()" : "NULL",
                        done ? "'sent-message-receipt'" : "NULL",
                        done ? "'COMPLETED'" : "NULL",
                        done ? "now()" : "NULL"));
        return id;
    }

    private static UUID publication(TestDatabase database, String kind) {
        UUID id = UUID.randomUUID();
        PracticeMigrationFixture.execute(database, """
                INSERT INTO event_publication(id,publication_date,listener_id,serialized_event,event_type)
                VALUES('%s',now(),'security-email',
                    jsonb_build_object('accountId',1,'kind','%s','occurredAt','2026-11-10T12:00:00Z')::text,
                    'de.tum.cit.aet.hephaestus.core.event.AccountSecurityChangedEvent')
                """.formatted(id, kind));
        return id;
    }

    private static String outcome(TestDatabase database, UUID id) {
        var rows = PracticeMigrationFixture.column(
                database, "SELECT state || ':' || stop_reason FROM agent_action WHERE id='" + id + "'");
        return Objects.requireNonNull(rows.getFirst());
    }
}
