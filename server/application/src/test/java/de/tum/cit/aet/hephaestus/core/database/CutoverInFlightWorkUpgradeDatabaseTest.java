package de.tum.cit.aet.hephaestus.core.database;

import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.column;
import static de.tum.cit.aet.hephaestus.core.database.PracticeMigrationFixture.execute;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The 1.0 upgrade leaves no conversation turn of the old release to run (ADR 0070 § In-flight work, decision F-15): a
 * queued turn ends with the reply that offers Ask again, and a running one loses its lease, so the server's lease
 * reaper ends and bills it ({@code MentorTurnCompletionIntegrationTest}). Every claimed review returns to the queue
 * before lease recovery sees it, with no owner, lease or execution start, its results kept and no retry spent.
 */
@Tag("database")
class CutoverInFlightWorkUpgradeDatabaseTest {
    private static final String CHANGELOG = "20261101014846-cutover-in-flight-work.xml";
    private static final String INTERRUPTED = "FAILED|Hephaestus was updated before it could answer. Ask again."
            + "|an upgrade to 1.0 stopped it before it ended|true|true";

    private static final String UNLEASED = "00000000-0000-0000-0000-000000997311";
    private static final String LEASED = "00000000-0000-0000-0000-000000997312";
    private static final String UNSTARTED = "00000000-0000-0000-0000-000000997313";
    private static final String QUEUED = "00000000-0000-0000-0000-000000997314";
    private static final String REQUEUED = "00000000-0000-0000-0000-000000997315";
    private static final String STOPPING = "00000000-0000-0000-0000-000000997316";
    private static final String STOPPED = "00000000-0000-0000-0000-000000997317";
    private static final String DONE = "00000000-0000-0000-0000-000000997318";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldLeaveNoTurnOfTheOldReleaseToRunAfterTheUpgrade(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        PracticeMigrationFixture.upgradeBefore(database, context, CHANGELOG);
        // One running turn per conversation and one thread per answer, so two child conversations.
        execute(database, """
            INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)
            SELECT '00000000-0000-0000-0000-000000997301'::uuid,workspace_id,developer_id,
                '2026-10-31T21:00:00Z'::timestamptz,'2026-10-31T23:00:00Z'::timestamptz
            FROM feedback WHERE id='00000000-0000-0000-0000-000000992193' AND workspace_id=992101;
            INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,created_at,queued_at,
                attempt,started_at,leased_until,finished_at)
            VALUES('%8$s',992101,'00000000-0000-0000-0000-000000997301','done','DONE','APP','2026-10-31T21:59:00Z',
                '2026-10-31T21:59:00Z',1,'2026-10-31T21:59:01Z',NULL,'2026-10-31T22:00:00Z');
            INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,created_at,queued_at,
                attempt,started_at,leased_until,finished_at)
            VALUES('00000000-0000-0000-0000-000000997319',992101,'00000000-0000-0000-0000-000000997301','done-2',
                'DONE','APP','2026-10-31T22:05:00Z','2026-10-31T22:05:00Z',1,'2026-10-31T22:05:01Z',NULL,
                '2026-10-31T22:06:00Z');
            INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,turn_id,created_at)
            VALUES('00000000-0000-0000-0000-000000997331',992101,'00000000-0000-0000-0000-000000997301',1,
                'HEPH','APP','An answer.','%8$s','2026-10-31T22:00:00Z'),
            ('00000000-0000-0000-0000-000000997332',992101,'00000000-0000-0000-0000-000000997301',2,
                'HEPH','APP','Another answer.','00000000-0000-0000-0000-000000997319','2026-10-31T22:06:00Z');
            INSERT INTO mentor_conversation(id,workspace_id,account_id,parent_conversation_id,parent_entry_id,
                created_at,last_activity_at)
            SELECT c.id,p.workspace_id,p.account_id,p.id,c.entry,
                '2026-10-31T22:07:00Z'::timestamptz,'2026-10-31T23:00:00Z'::timestamptz
            FROM mentor_conversation p
            CROSS JOIN (VALUES ('00000000-0000-0000-0000-000000997302'::uuid,
                                '00000000-0000-0000-0000-000000997331'::uuid),
                               ('00000000-0000-0000-0000-000000997303'::uuid,
                                '00000000-0000-0000-0000-000000997332'::uuid)) AS c(id,entry)
            WHERE p.id='00000000-0000-0000-0000-000000997301';
            INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,created_at,queued_at,
                attempt,started_at,leased_until,finished_at)
            VALUES('%1$s',992101,'00000000-0000-0000-0000-000000997301','unleased','RUNNING','APP',
                '2026-10-31T23:00:00Z','2026-10-31T23:00:00Z',1,'2026-10-31T23:00:01Z',NULL,NULL),
            ('%2$s',992101,'00000000-0000-0000-0000-000000997302','leased','RUNNING','APP','2026-10-31T23:01:00Z',
                '2026-10-31T23:01:00Z',1,'2026-10-31T23:01:01Z',now() + interval '5 minutes',NULL),
            ('%3$s',992101,'00000000-0000-0000-0000-000000997303','unstarted','RUNNING','APP','2026-10-31T23:02:00Z',
                '2026-10-31T23:02:00Z',1,NULL,NULL,NULL),
            ('%4$s',992101,'00000000-0000-0000-0000-000000997301','queued','QUEUED','APP','2026-10-31T23:03:00Z',
                '2026-10-31T23:03:00Z',0,NULL,NULL,NULL),
            ('%5$s',992101,'00000000-0000-0000-0000-000000997302','requeued','QUEUED','APP','2026-10-30T23:00:00Z',
                '2026-10-30T23:06:00Z',1,NULL,NULL,NULL),
            ('%6$s',992101,'00000000-0000-0000-0000-000000997303','stopping','STOPPED','APP','2026-10-31T22:29:00Z',
                '2026-10-31T22:29:00Z',1,'2026-10-31T22:29:01Z',now() + interval '5 minutes',
                '2026-10-31T22:30:00Z'),
            ('%7$s',992101,'00000000-0000-0000-0000-000000997301','stopped','STOPPED','APP','2026-10-31T22:19:00Z',
                '2026-10-31T22:19:00Z',1,'2026-10-31T22:19:01Z',NULL,'2026-10-31T22:20:00Z');
            """.formatted(UNLEASED, LEASED, UNSTARTED, QUEUED, REQUEUED, STOPPING, STOPPED, DONE));

        PracticeMigrationFixture.upgrade(database, context);

        for (String turn : new String[] {QUEUED, REQUEUED}) {
            assertThat(column(
                            database,
                            "SELECT status || '|' || error || '|' || failure_cause || '|'"
                                    + " || (finished_at IS NOT NULL) || '|' || (leased_until IS NULL)"
                                    + " FROM mentor_turn WHERE id='" + turn + "'"))
                    .as("a queued turn of the old release never starts")
                    .containsExactly(INTERRUPTED);
        }
        assertThat(column(
                        database,
                        "SELECT id::text || '|' || status || '|' || (leased_until IS NULL) || '|'"
                                + " || to_char(started_at AT TIME ZONE 'UTC','HH24:MI:SS') FROM mentor_turn"
                                + " WHERE id IN ('" + UNLEASED + "','" + LEASED + "','" + UNSTARTED + "') ORDER BY id"))
                .as("a running turn waits for the lease reaper, which ends and bills it; it needs its start")
                .containsExactly(
                        UNLEASED + "|RUNNING|true|23:00:01",
                        LEASED + "|RUNNING|true|23:01:01",
                        UNSTARTED + "|RUNNING|true|23:02:00");
        assertThat(column(
                        database,
                        "SELECT count(*)::text FROM mentor_turn"
                                + " WHERE status = 'QUEUED' OR (status = 'RUNNING' AND leased_until IS NOT NULL)"))
                .as("no turn of the old release can be claimed or kept running")
                .containsExactly("0");
        assertThat(column(
                        database,
                        "SELECT id::text || '|' || status || '|' || coalesce(error,'-') || '|'"
                                + " || (leased_until IS NULL) || '|' || to_char(finished_at AT TIME ZONE 'UTC','HH24:MI')"
                                + " FROM mentor_turn WHERE id IN ('" + STOPPING + "','" + STOPPED + "','" + DONE
                                + "') ORDER BY id"))
                .as("the developer's own Stop stays; the reaper takes back the lease that its attempt holds")
                .containsExactly(
                        STOPPING + "|STOPPED|-|false|22:30",
                        STOPPED + "|STOPPED|-|true|22:20",
                        DONE + "|DONE|-|true|22:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRequeueEachClaimedReviewWithItsUsedRetriesUnchangedAndKeepItsResults(String context) throws Exception {
        TestDatabase database = PracticeMigrationFixture.releasedDatabase(context);
        // The v0.95.1 schema: it ends before the first change set in next/, which keeps its logical file name.
        PracticeMigrationFixture.upgradeBefore(database, context, "1793058726214_changelog.xml");
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/result-parent.sql"))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
        // One review for each active job: a review has at most one queued or running job.
        execute(database, """
                INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at)
                SELECT ('00000000-0000-0000-0000-00000097741' || n)::uuid,workspace_id,item_id,kind,
                       'cutover-' || n,origin,status,now()
                FROM review, generate_series(1,4) AS n
                WHERE id='00000000-0000-0000-0000-000000978011'
                """);
        execute(database, """
                INSERT INTO review_job
                    (id,review_id,workspace_id,submitted_at,status,processing_status,trace_id,input,config_snapshot,
                     attempt,drain_count,worker_id,leased_until,started_at,admission_digest,admission_attempt,capture)
                SELECT ('00000000-0000-0000-0000-00000097740' || n)::uuid,
                       ('00000000-0000-0000-0000-00000097741' || n)::uuid,978001,now(),'RUNNING','NONE',
                       '00000000000000000000000000000001','{}','{}',attempt,drains,worker,leased,now(),digest,admitted,
                       capture::jsonb
                FROM (VALUES
                    -- Claimed by 0.95.1, which leases every claim.
                    (1,1,0,'worker-0951',now() + interval '5 minutes',NULL,NULL::int,NULL),
                    -- Admitted under its lease: its results are staged.
                    (2,1,0,'worker-0951',now() + interval '5 minutes',repeat('a',64),1,'{"staged":"kept"}'),
                    -- Drained: its lease is gone and its drain is counted.
                    (3,2,1,'worker-0951',NULL::timestamptz,NULL,NULL,NULL),
                    -- Drained once before, then claimed again under a lease.
                    (4,3,1,'worker-0951',now() + interval '5 minutes',NULL,NULL,NULL))
                    AS job(n,attempt,drains,worker,leased,digest,admitted,capture)
                """);
        var usedBefore = column(
                database,
                "SELECT id || ':' || (attempt - drain_count - room_refusals) FROM review_job"
                        + " WHERE id::text LIKE '%97740%' ORDER BY id");
        assertThat(usedBefore).hasSize(4);

        PracticeMigrationFixture.upgrade(database, context);

        assertThat(column(
                        database,
                        "SELECT id || ':' || (attempt - drain_count - room_refusals) FROM review_job"
                                + " WHERE id::text LIKE '%97740%' ORDER BY id"))
                .as("no requeued review spends a retry")
                .containsExactlyElementsOf(usedBefore);
        assertThat(column(database, """
                SELECT right(id::text,1) || '|' || status || '|' || attempt || '|' || (lease_id IS NULL) || '|'
                    || (leased_until IS NULL) || '|' || (started_at IS NULL) || '|'
                    || (execution_started_at IS NULL) || '|' || coalesce(admission_attempt::text,'-') || '|'
                    || coalesce(left(admission_digest,4),'-') || '|' || coalesce(capture->>'staged','-')
                FROM review_job WHERE id::text LIKE '%97740%' ORDER BY id
                """))
                .as(
                        "every claim is queued with no owner, lease or fence; an admitted one takes the next attempt and drain")
                .containsExactly(
                        "1|QUEUED|1|true|true|true|true|-|-|-",
                        "2|QUEUED|2|true|true|true|true|1|aaaa|kept",
                        "3|QUEUED|2|true|true|true|true|-|-|-",
                        "4|QUEUED|3|true|true|true|true|-|-|-");
    }
}
