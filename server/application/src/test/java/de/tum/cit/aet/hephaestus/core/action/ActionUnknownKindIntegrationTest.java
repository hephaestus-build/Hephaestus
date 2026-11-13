package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.core.tenancy.TenancyBypass;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.ObjectMapper;

/**
 * A rolling update: a newer replica wrote an action that this build cannot decode, either of a kind that it does not
 * declare, or of a known kind with a variant that its adapter does not declare. The build must leave the row as it
 * is, count it nowhere, and still queue the supported actions of its lane behind it. The admission comes from the real
 * adapters of the server.
 *
 * <p>The schema owner relaxes {@code ck_agent_action_kind} in one transaction that owns every statement of a test, and
 * rolls it back: the application role cannot alter the table, and a second connection would wait for the lock of the
 * first. The store, the repository and the sampler therefore run on that connection, not on the shared context's pool.
 */
class ActionUnknownKindIntegrationTest extends BaseIntegrationTest {
    private static final String FUTURE_KIND = "FUTURE_KIND";
    private static final long WORKSPACE_ID = 997_950L;
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final String ANY = "{\"n\":1}";

    /** Emails of notices that this build has, and emails of variants that it lacks. */
    private static final String KNOWN_EMAIL = "{\"kind\":\"TEST_MESSAGE\",\"event\":{}}";

    private static final String KNOWN_EMAIL_WITH_ENUM =
            "{\"kind\":\"ACCOUNT_SECURITY_CHANGED\",\"event\":{\"kind\":\"PASSKEY_CHANGED\"}}";
    private static final String KNOWN_EMAIL_WITHOUT_ENUM = "{\"kind\":\"ACCOUNT_SECURITY_CHANGED\",\"event\":{}}";
    private static final String FUTURE_EMAIL_KIND = "{\"kind\":\"FUTURE_NOTICE\",\"event\":{}}";
    private static final String FUTURE_EMAIL_ENUM =
            "{\"kind\":\"ACCOUNT_SECURITY_CHANGED\",\"event\":{\"kind\":\"FUTURE_CHANGE\"}}";
    private static final String UNTAGGED_EMAIL = "{\"event\":{}}";

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private List<ActionAdapter> adapters;

    @Autowired
    private JdbcTemplate jdbc;

    // Micrometer gauges hold their state object by weak reference. The sampler owns that state, so the test keeps it
    // for the meter's lifetime, as the Spring singleton does.
    private @Nullable ActionBacklogSampler sampler;

    /** The outbox on the owner's connection. */
    private record Outbox(
            JdbcTemplate sql, AgentActionRepository repository, ActionStore store, ActionAdmission admission) {

        UUID admitted(String kind, String payload, String lane, String due, String expires) {
            return admitted(kind, payload, lane, "key:" + lane + ":" + TimeOrderedIds.next(), due, expires);
        }

        UUID admitted(String kind, String payload, String lane, String key, String due, String expires) {
            UUID id = TimeOrderedIds.next();
            sql.update("""
                    INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                      attempts, max_attempts, next_attempt_at, expires_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ADMITTED', CAST(? AS jsonb), 0, 3, now() + CAST(? AS interval),
                      now() + CAST(? AS interval), now(), now())
                    """, id, WORKSPACE_ID, kind, lane, key, payload, due, expires);
            return id;
        }

        UUID executing(String kind, String payload, String lane, String lease, int attempts) {
            UUID id = TimeOrderedIds.next();
            sql.update("""
                    INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, payload,
                      attempts, max_attempts, lease_epoch, leased_until, next_attempt_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'EXECUTING', CAST(? AS jsonb), ?, 3, ?, now() + CAST(? AS interval),
                      now(), now(), now())
                    """, id, WORKSPACE_ID, kind, lane, "key:" + id, payload, attempts, attempts, lease);
            return id;
        }

        UUID done(String kind, String lane, String endedAgo) {
            UUID id = TimeOrderedIds.next();
            sql.update("""
                    INSERT INTO agent_action (id, workspace_id, kind, lane_key, idempotency_key, state, stop_reason,
                      attempts, max_attempts, next_attempt_at, ended_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'DONE', 'COMPLETED', 1, 3, now(), now() - CAST(? AS interval),
                      now() - CAST(? AS interval), now() - CAST(? AS interval))
                    """, id, WORKSPACE_ID, kind, lane, "key:" + id, endedAgo, endedAgo, endedAgo);
            return id;
        }

        /** The whole row, so a test can prove that nothing of it changed. */
        String snapshot(UUID id) {
            return Objects.requireNonNull(
                    sql.queryForObject("SELECT to_jsonb(a)::text FROM agent_action a WHERE id = ?", String.class, id));
        }

        /** The admitted emails that the backlog counts. */
        long admittedEmails() {
            return repository.backlog(admission.json()).stream()
                    .filter(row ->
                            row.getKind().equals("EMAIL") && row.getState().equals("ADMITTED"))
                    .mapToLong(AgentActionRepository.BacklogRow::getActions)
                    .sum();
        }

        void withdraw(UUID id) {
            sql.update(
                    "UPDATE agent_action SET state = 'CANCELLED', stop_reason = 'WITHDRAWN', payload = NULL,"
                            + " ended_at = now(), updated_at = now() WHERE id = ?",
                    id);
        }

        boolean exists(UUID id) {
            return Objects.requireNonNull(
                            sql.queryForObject("SELECT count(*) FROM agent_action WHERE id = ?", Long.class, id))
                    == 1;
        }
    }

    @Test
    void shouldDeclareAJsonPathForEveryKindOfTheServer() {
        var paths = mapper.readTree(ActionAdmission.of(adapters).json());

        for (ActionKind kind : ActionKind.values()) {
            String path = paths.path(kind.name()).asString();
            assertThat(path).as("the declaration of %s", kind).isNotEmpty();
            assertThat(jdbc.queryForObject("SELECT CAST(? AS jsonpath)::text", String.class, path))
                    .as("PostgreSQL parses the path of %s", kind)
                    .isNotEmpty();
        }
    }

    @Test
    void shouldLeaveRowsOfAnUnknownKindUntouchedWhileKnownRowsRunTheirCourse() throws SQLException {
        inRolledBackTransaction(outbox -> {
            String lane = "lane:" + UUID.randomUUID();
            UUID dueKnown = outbox.admitted("EMAIL", KNOWN_EMAIL, lane + ":due", "-1 minute", "1 day");
            UUID reclaimKnown = outbox.executing("EMAIL", KNOWN_EMAIL, lane + ":reclaim", "-1 minute", 1);
            UUID expiredKnown = outbox.admitted("EMAIL", KNOWN_EMAIL, lane + ":expired", "-2 minutes", "-1 minute");
            UUID lostKnown = outbox.executing("EMAIL", KNOWN_EMAIL, lane + ":lost", "-1 minute", 3);
            UUID oldKnown = outbox.done("EMAIL", lane + ":old", "400 days");

            UUID dueUnknown = outbox.admitted(FUTURE_KIND, ANY, lane + ":due-unknown", "-1 minute", "1 day");
            UUID reclaimUnknown = outbox.executing(FUTURE_KIND, ANY, lane + ":reclaim-unknown", "-1 minute", 1);
            UUID expiredUnknown =
                    outbox.admitted(FUTURE_KIND, ANY, lane + ":expired-unknown", "-2 minutes", "-1 minute");
            UUID lostUnknown = outbox.executing(FUTURE_KIND, ANY, lane + ":lost-unknown", "-1 minute", 3);
            UUID oldUnknown = outbox.done(FUTURE_KIND, lane + ":old-unknown", "400 days");
            List<UUID> unknown = List.of(dueUnknown, reclaimUnknown, expiredUnknown, lostUnknown, oldUnknown);
            List<String> before = unknown.stream().map(outbox::snapshot).toList();

            var meters = new SimpleMeterRegistry();
            sampler = new ActionBacklogSampler(outbox.repository(), adapters, meters);
            sampler.sample();
            assertThat(meters.get(CoreMetrics.ACTION_BACKLOG_SAMPLER_FAILURES)
                            .counter()
                            .count())
                    .as("a kind that the build cannot name does not fail the pass")
                    .isZero();
            assertThat(meters.get(CoreMetrics.ACTION_BACKLOG)
                            .tag("kind", "EMAIL")
                            .tag("state", "ADMITTED")
                            .gauge()
                            .value())
                    .as("the known admitted rows are counted")
                    .isGreaterThanOrEqualTo(2);
            assertThat(outbox.repository().backlog(outbox.admission().json()))
                    .extracting(AgentActionRepository.BacklogRow::getKind)
                    .doesNotContain(FUTURE_KIND);

            assertThat(outbox.store().claim(1000, LEASE, outbox.admission()))
                    .extracting(ActionStore.Claim::id)
                    .as("known rows claim, unknown ones do not")
                    .contains(dueKnown, reclaimKnown)
                    .doesNotContainAnyElementsOf(unknown);
            for (UUID id : unknown)
                assertThat(outbox.store().claim(id, LEASE, outbox.admission())).isEmpty();

            assertThat(outbox.store().findStale(1000, outbox.admission()))
                    .contains(expiredKnown, lostKnown)
                    .doesNotContainAnyElementsOf(unknown);
            for (UUID id : unknown)
                assertThat(outbox.store().endStale(id, outbox.admission(), ended -> {}))
                        .isEmpty();
            assertThat(outbox.store().endStale(expiredKnown, outbox.admission(), ended -> {}))
                    .get()
                    .satisfies(settled -> assertThat(settled.reason()).isEqualTo(ActionStopReason.EXPIRED));
            assertThat(outbox.store().endStale(lostKnown, outbox.admission(), ended -> {}))
                    .isPresent();

            assertThat(outbox.store().deleteEnded(Duration.ofDays(30))).isGreaterThanOrEqualTo(1);
            assertThat(outbox.exists(oldKnown))
                    .as("the known outcome past retention goes")
                    .isFalse();
            assertThat(outbox.exists(oldUnknown))
                    .as("a newer build keeps its own")
                    .isTrue();

            assertThat(unknown.stream().map(outbox::snapshot).toList())
                    .as("state, attempts, payload and lease of every unknown row")
                    .containsExactlyElementsOf(before);
        });
    }

    @Test
    void shouldLeaveRowsOfAKnownKindWithAnUnknownNestedVariantUntouched() throws SQLException {
        inRolledBackTransaction(outbox -> {
            String lane = "lane:" + UUID.randomUUID();
            String future = "feedback:" + UUID.randomUUID() + ":1:FUTURE_SEND";
            String known = "feedback:" + UUID.randomUUID() + ":1:POST";
            // Each unknown variant is due, expired, lost and reclaimable, each in a lane of its own.
            var unknown = new ArrayList<UUID>();
            for (String payload : List.of(FUTURE_EMAIL_KIND, FUTURE_EMAIL_ENUM, UNTAGGED_EMAIL)) {
                String at = lane + ":" + unknown.size();
                unknown.add(outbox.admitted("EMAIL", payload, at + ":due", "-1 minute", "1 day"));
                unknown.add(outbox.admitted("EMAIL", payload, at + ":expired", "-2 minutes", "-1 minute"));
                unknown.add(outbox.executing("EMAIL", payload, at + ":lost", "-1 minute", 3));
                unknown.add(outbox.executing("EMAIL", payload, at + ":reclaim", "-1 minute", 1));
            }
            String sendLane = "feedback:" + UUID.randomUUID();
            unknown.add(outbox.admitted("DELIVER_FEEDBACK", "{}", sendLane + ":a", future, "-1 minute", "1 day"));
            unknown.add(outbox.admitted(
                    "DELIVER_FEEDBACK", "{}", sendLane + ":b", future + "X", "-2 minutes", "-1 minute"));

            UUID plain = outbox.admitted("EMAIL", KNOWN_EMAIL, lane + ":plain", "-1 minute", "1 day");
            UUID withEnum = outbox.admitted("EMAIL", KNOWN_EMAIL_WITH_ENUM, lane + ":enum", "-1 minute", "1 day");
            UUID withoutEnum = outbox.admitted("EMAIL", KNOWN_EMAIL_WITHOUT_ENUM, lane + ":none", "-1 minute", "1 day");
            UUID send = outbox.admitted("DELIVER_FEEDBACK", "{}", sendLane + ":c", known, "-1 minute", "1 day");
            UUID expiredKnown = outbox.admitted("EMAIL", KNOWN_EMAIL, lane + ":gone", "-2 minutes", "-1 minute");
            List<String> before = unknown.stream().map(outbox::snapshot).toList();

            var meters = new SimpleMeterRegistry();
            sampler = new ActionBacklogSampler(outbox.repository(), adapters, meters);
            sampler.sample();
            assertThat(meters.get(CoreMetrics.ACTION_BACKLOG_SAMPLER_FAILURES)
                            .counter()
                            .count())
                    .isZero();
            long counted = outbox.admittedEmails();
            outbox.admitted("EMAIL", FUTURE_EMAIL_KIND, lane + ":more-kind", "-1 minute", "1 day");
            outbox.admitted("EMAIL", FUTURE_EMAIL_ENUM, lane + ":more-enum", "-1 minute", "1 day");
            assertThat(outbox.admittedEmails())
                    .as("an unknown variant adds nothing to the backlog")
                    .isEqualTo(counted);
            outbox.admitted("EMAIL", KNOWN_EMAIL, lane + ":more-known", "-1 minute", "1 day");
            assertThat(outbox.admittedEmails()).as("a known one does").isEqualTo(counted + 1);

            assertThat(outbox.store().claim(1000, LEASE, outbox.admission()))
                    .extracting(ActionStore.Claim::id)
                    .as("a known variant claims, also with an enum that is missing; an unknown one never does")
                    .contains(plain, withEnum, withoutEnum, send)
                    .doesNotContainAnyElementsOf(unknown);
            for (UUID id : unknown)
                assertThat(outbox.store().claim(id, LEASE, outbox.admission())).isEmpty();

            assertThat(outbox.store().findStale(1000, outbox.admission()))
                    .contains(expiredKnown)
                    .doesNotContainAnyElementsOf(unknown);
            for (UUID id : unknown)
                assertThat(outbox.store().endStale(id, outbox.admission(), ended -> {}))
                        .as("the reaper, by id, leaves an unknown variant")
                        .isEmpty();
            assertThat(outbox.store().endStale(expiredKnown, outbox.admission(), ended -> {}))
                    .isPresent();

            assertThat(unknown.stream().map(outbox::snapshot).toList())
                    .as("state, attempts, payload and lease of every unknown variant")
                    .containsExactlyElementsOf(before);
        });
    }

    @Test
    void shouldHoldTheLaneBehindAnUndecodableRowThatIsEarlierOrLive() throws SQLException {
        inRolledBackTransaction(outbox -> {
            String earlier = "lane:" + UUID.randomUUID();
            UUID earlierUnknown = outbox.admitted(FUTURE_KIND, ANY, earlier, "-1 minute", "1 day");
            UUID behindEarlier = outbox.admitted("EMAIL", KNOWN_EMAIL, earlier, "-1 minute", "1 day");
            String live = "lane:" + UUID.randomUUID();
            UUID liveUnknown = outbox.executing(FUTURE_KIND, ANY, live, "1 minute", 1);
            UUID behindLive = outbox.admitted("EMAIL", KNOWN_EMAIL, live, "-1 minute", "1 day");
            String abandoned = "lane:" + UUID.randomUUID();
            UUID abandonedUnknown = outbox.executing(FUTURE_KIND, ANY, abandoned, "-1 minute", 1);
            UUID behindAbandoned = outbox.admitted("EMAIL", KNOWN_EMAIL, abandoned, "-1 minute", "1 day");
            String nested = "lane:" + UUID.randomUUID();
            UUID nestedUnknown = outbox.admitted("EMAIL", FUTURE_EMAIL_KIND, nested, "-1 minute", "1 day");
            UUID behindNested = outbox.admitted("EMAIL", KNOWN_EMAIL, nested, "-1 minute", "1 day");
            String free = "lane:" + UUID.randomUUID();
            UUID alone = outbox.admitted("EMAIL", KNOWN_EMAIL, free, "-1 minute", "1 day");
            List<UUID> blocked = List.of(behindEarlier, behindLive, behindAbandoned, behindNested);

            assertThat(outbox.store().claim(1000, LEASE, outbox.admission()))
                    .extracting(ActionStore.Claim::id)
                    .contains(alone)
                    .doesNotContainAnyElementsOf(blocked)
                    .doesNotContain(earlierUnknown, liveUnknown, abandonedUnknown, nestedUnknown);
            for (UUID id : blocked)
                assertThat(outbox.store().claim(id, LEASE, outbox.admission())).isEmpty();

            outbox.withdraw(earlierUnknown);
            assertThat(outbox.store().claim(behindEarlier, LEASE, outbox.admission()))
                    .as("the supported action runs once the unknown one before it has ended")
                    .isPresent();
        });
    }

    // Closing the bypass scope is the operation; its binding is intentionally unread.
    @SuppressWarnings("try")
    private void inRolledBackTransaction(Consumer<Outbox> body) throws SQLException {
        var owner = PostgreSQLTestContainer.owner();
        try (Connection connection = DriverManager.getConnection(owner.jdbcUrl(), owner.username(), owner.password())) {
            connection.setAutoCommit(false);
            try (var session = entityManagerFactory
                    .unwrap(SessionFactory.class)
                    .withOptions()
                    .connection(connection)
                    .openSession()) {
                var transaction = session.beginTransaction();
                try {
                    var sql = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                    // A held lock must fail this test; it must not wait for the task's timeout.
                    sql.execute("SET LOCAL lock_timeout = '30s'");
                    sql.execute("ALTER TABLE agent_action DROP CONSTRAINT ck_agent_action_kind");
                    sql.update(
                            """
                            INSERT INTO workspace (id, slug, display_name, account_login, account_type, status)
                            VALUES (?, ?, 'Unknown kind', ?, 'ORG', 'ACTIVE')
                            """,
                            WORKSPACE_ID,
                            "unknown-kind-" + UUID.randomUUID(),
                            "unknown-kind-" + UUID.randomUUID());
                    var repository = new JpaRepositoryFactory(session).getRepository(AgentActionRepository.class);
                    // The repository has no Spring proxy here, so nothing opens the scope that each of its
                    // WorkspaceAgnostic methods opens in production.
                    try (var ignored = TenancyBypass.open("outbox statements of an unknown kind")) {
                        body.accept(new Outbox(
                                sql, repository, new ActionStore(repository, mapper), ActionAdmission.of(adapters)));
                    }
                } finally {
                    transaction.rollback();
                }
            }
        }
    }
}
