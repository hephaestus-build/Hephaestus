package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class PersonRowSecurityMigrationTest {
    @Test
    void shouldNameTheStoppedUpgradeForEachLiveLeaseAndMigrateAfterTheLeasesExpire() throws Exception {
        String name = "person_row_security_" + UUID.randomUUID().toString().replace("-", "");
        var database = PostgreSQLTestContainer.createDatabase(name);
        PostgreSQLTestContainer.applyDatabaseRoles(name);
        var owner = PostgreSQLTestContainer.asOwner(database);
        PracticeMigrationFixture.upgradeBefore(owner, "prod", "-account-export-row-security.xml");
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO workspace(id, slug, display_name, account_login, account_type, status)
                VALUES (979116, 'rls-fence', 'RLS fence', 'rls-fence', 'ORG', 'ACTIVE');
                INSERT INTO agent_run(id, workspace_id, kind, band, concurrency_key, state,
                    lease_epoch, leased_until, started_at)
                VALUES (gen_random_uuid(), 979116, 'REVIEW', 'BATCH', 'lease-fence',
                    'LEASED', 1, now() + interval '1 day', now());
                """);
        refusesLiveLease(owner);
        PracticeMigrationFixture.execute(owner, """
                UPDATE agent_run SET leased_until = now() - interval '1 day' WHERE workspace_id = 979116
                """);

        insertAction(owner, "1 day");
        refusesLiveLease(owner);
        PracticeMigrationFixture.execute(owner, "DELETE FROM agent_action WHERE idempotency_key = 'rls-fence'");

        insertAction(owner, "-1 day");
        PostgreSQLTestContainer.migrateDatabase(owner, "prod");
    }

    private static void refusesLiveLease(TestDatabase owner) {
        assertThatThrownBy(() -> PostgreSQLTestContainer.migrateDatabase(owner, "prod"))
                .hasStackTraceContaining("Stop every role before enabling person row-level security");
    }

    private static void insertAction(TestDatabase owner, String remaining) {
        PracticeMigrationFixture.execute(owner, """
                INSERT INTO agent_action(id, created_at, idempotency_key, kind, lane_key, lease_epoch,
                    leased_until, max_attempts, next_attempt_at, payload, state, updated_at)
                VALUES (gen_random_uuid(), now() - interval '3 days', 'rls-fence', 'EMAIL', 'rls-fence', 1,
                    now() + interval '%s', 1, now(), '{}', 'EXECUTING', now());
                """.formatted(remaining));
    }
}
